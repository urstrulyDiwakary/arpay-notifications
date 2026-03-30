package com.arpay.worker;

import com.arpay.entity.NotificationDlqEntry;
import com.arpay.entity.NotificationOutbox;
import com.arpay.entity.OutboxStatus;
import com.arpay.repository.NotificationDlqEntryRepository;
import com.arpay.repository.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * DLQ Retry Scheduler — retries DLQ entries with exponential backoff
 * and periodically re-dispatches stuck outbox entries.
 *
 * <p>DLQ entries are created by {@link NotificationWorker} when an outbox entry
 * exceeds its max retry count. This scheduler resolves them by resetting the
 * corresponding outbox entry to PENDING and re-dispatching to the worker.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DLQRetryScheduler {

    private final NotificationDlqEntryRepository dlqRepository;
    private final NotificationOutboxRepository outboxRepository;
    private final NotificationWorker notificationWorker;

    @Value("${notifications.dlq.retry.enabled:true}")
    private boolean retryEnabled;

    @Value("${notifications.dlq.retry.batch-size:100}")
    private int batchSize;

    @Value("${notifications.dlq.retry.max-cycles:3}")
    private int maxCycles;

    /** Base delay for exponential backoff: retryN waits (baseDelay * 2^N) seconds, capped at 1 hour. */
    @Value("${notifications.dlq.retry.base-delay-seconds:60}")
    private int baseDelaySeconds;

    // -----------------------------------------------------------------------
    // DLQ retry with exponential backoff
    // -----------------------------------------------------------------------

    /**
     * Retry DLQ entries whose {@code next_retry_at} has passed.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRateString = "${notifications.dlq.retry.interval-ms:300000}")
    @Transactional
    public void retryDueEntries() {
        if (!retryEnabled) {
            log.debug("DLQ retry is disabled");
            return;
        }

        List<NotificationDlqEntry> dueEntries = dlqRepository.findDueForRetry(
                LocalDateTime.now(), PageRequest.of(0, batchSize)).getContent();

        if (dueEntries.isEmpty()) {
            log.debug("No DLQ entries due for retry");
            return;
        }

        log.info("DLQ retry: found {} entries due for retry", dueEntries.size());

        int success = 0, failed = 0, abandoned = 0;

        for (NotificationDlqEntry entry : dueEntries) {
            try {
                Result result = processRetry(entry);
                switch (result) {
                    case SUCCESS   -> success++;
                    case FAILED    -> failed++;
                    case MAX_RETRIES -> abandoned++;
                }
            } catch (Exception e) {
                log.error("DLQ retry threw for eventId={}: {}", entry.getEventId(), e.getMessage());
                failed++;
            }
        }

        log.info("DLQ retry cycle done: success={}, failed={}, abandoned={}", success, failed, abandoned);
    }

    private Result processRetry(NotificationDlqEntry entry) {
        if (entry.getRetryCount() >= maxCycles) {
            log.warn("DLQ entry abandoned (max cycles {}): eventId={}", maxCycles, entry.getEventId());
            // Keep resolved=false so ops team can see it; do not re-schedule
            entry.setNextRetryAt(null);
            dlqRepository.save(entry);
            return Result.MAX_RETRIES;
        }

        // --- Exponential back-off with jitter ---
        // Formula: delay = base × 2ⁿ + random(0–30%)
        // This prevents retry storms by spreading out retries
        long baseDelay = baseDelaySeconds * (1L << entry.getRetryCount());
        long jitter = (long) (baseDelay * 0.3 * Math.random()); // 0-30% jitter
        long delaySecs = Math.min(3600L, baseDelay + jitter);
        
        entry.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySecs));
        entry.setRetryCount(entry.getRetryCount() + 1);
        entry.setLastRetryAt(LocalDateTime.now());

        // Find the original outbox entry and reset it to PENDING so the worker can re-deliver
        Optional<NotificationOutbox> outboxOpt = outboxRepository.findByNotificationEventId(entry.getEventId());
        if (outboxOpt.isEmpty()) {
            log.warn("DLQ retry: original outbox not found for eventId={}", entry.getEventId());
            entry.setErrorMessage("Outbox entry not found during DLQ retry");
            dlqRepository.save(entry);
            return Result.FAILED;
        }

        NotificationOutbox outbox = outboxOpt.get();
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setErrorMessage(null);
        outboxRepository.save(outbox);

        // Re-dispatch — back-off is tracked in the DLQ entry; delivery tracking is in DeliveryIdempotencyKey
        notificationWorker.processOutboxEntry(outbox.getId());

        entry.setErrorMessage("Re-dispatched to worker (attempt " + entry.getRetryCount() + "/" + maxCycles + ")");
        dlqRepository.save(entry);

        log.info("DLQ entry re-dispatched: eventId={}, attempt={}/{}, nextRetryIn={}s",
                 entry.getEventId(), entry.getRetryCount(), maxCycles, delaySecs);
        return Result.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Stuck outbox recovery (FAILED entries)
    // -----------------------------------------------------------------------

    /**
     * Reset FAILED outbox entries back to PENDING and re-dispatch them.
     * Runs every 10 minutes with jitter to prevent thundering herd.
     */
    @Scheduled(fixedRateString = "${notifications.outbox.retry.interval-ms:600000}")
    @Transactional
    public void retryFailedOutbox() {
        List<NotificationOutbox> failed = outboxRepository.findDueForRetry(3);

        if (failed.isEmpty()) {
            log.debug("No FAILED outbox entries to retry");
            return;
        }

        log.info("Retrying {} FAILED outbox entries", failed.size());

        // Add small random delay before processing each entry (0-5 seconds)
        // This prevents thundering herd when many entries are retried together
        for (NotificationOutbox outbox : failed) {
            try {
                // Jitter: random 0-5 second delay
                long jitter = (long) (5000 * Math.random());
                Thread.sleep(jitter);
                
                outbox.setStatus(OutboxStatus.PENDING);
                outbox.setErrorMessage(null);
                outboxRepository.save(outbox);
                notificationWorker.processOutboxEntry(outbox.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Outbox retry interrupted");
                break;
            } catch (Exception e) {
                log.error("Failed to retry outbox id={}: {}", outbox.getId(), e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cleanup jobs
    // -----------------------------------------------------------------------

    /** Remove resolved DLQ entries older than 7 days. */
    @Scheduled(cron = "${notifications.dlq.cleanup.cron:0 0 2 * * ?}")
    @Transactional
    public void cleanupOldDlqEntries() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        log.info("DLQ cleanup: removing resolved entries older than {}", threshold);
        // Resolved entries with old nextRetryAt (null = no more retries)
        dlqRepository.findAll().stream()
                .filter(e -> Boolean.TRUE.equals(e.getResolved()) && e.getCreatedAt().isBefore(threshold))
                .forEach(dlqRepository::delete);
    }

    /** Remove old published outbox entries older than 24 hours. */
    @Scheduled(cron = "${notifications.outbox.cleanup.cron:0 0 3 * * ?}")
    @Transactional
    public void cleanupOldOutbox() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);
        int deleted = outboxRepository.deleteOldPublished(threshold);
        log.info("Cleaned up {} old published outbox entries", deleted);
    }

    private enum Result {
        SUCCESS, FAILED, MAX_RETRIES
    }
}
