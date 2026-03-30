package com.arpay.worker;

import com.arpay.entity.NotificationOutbox;
import com.arpay.repository.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * On application startup, recovers outbox entries that were left in a non-terminal
 * state (PENDING or QUEUED) from a previous run — e.g., because the JVM crashed
 * after dispatch but before the worker could mark them PUBLISHED.
 *
 * <p>Recovery is safe: the {@link NotificationWorker} is idempotent via
 * {@link com.arpay.entity.DeliveryIdempotencyKey}, so re-dispatching the same
 * entry twice will not produce a duplicate push notification.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxStartupRecovery implements ApplicationRunner {

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationWorker notificationWorker;

    @Value("${notifications.recovery.enabled:true}")
    private boolean recoveryEnabled;

    /**
     * QUEUED entries older than this many minutes are considered "stuck"
     * (worker thread that claimed them has likely crashed).
     */
    @Value("${notifications.recovery.stuck-minutes:5}")
    private int stuckMinutes;

    @Value("${notifications.recovery.batch-size:200}")
    private int batchSize;

    @Override
    public void run(ApplicationArguments args) {
        if (!recoveryEnabled) {
            log.info("Outbox startup recovery is disabled");
            return;
        }

        try {
            // Brief pause so connection pools and other beans are fully ready
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        recoverStuckEntries();
    }

    private void recoverStuckEntries() {
        // PENDING entries were never dispatched (crash before AFTER_COMMIT listener fired)
        List<NotificationOutbox> pending = outboxRepository.findPendingWithLimit("PENDING", batchSize);

        // QUEUED entries were dispatched but never processed (crash mid-worker)
        List<NotificationOutbox> stuckQueued = outboxRepository.findStuckQueued(
                LocalDateTime.now().minusMinutes(stuckMinutes), batchSize);

        List<NotificationOutbox> toRecover = new ArrayList<>(pending);
        toRecover.addAll(stuckQueued);

        if (toRecover.isEmpty()) {
            log.info("Startup recovery: no stuck outbox entries found");
            return;
        }

        log.warn("Startup recovery: re-dispatching {} stuck entries (PENDING={}, stuckQUEUED={})",
                 toRecover.size(), pending.size(), stuckQueued.size());

        int dispatched = 0;
        for (NotificationOutbox outbox : toRecover) {
            try {
                notificationWorker.processOutboxEntry(outbox.getId());
                dispatched++;
            } catch (Exception e) {
                log.error("Startup recovery dispatch failed for outboxId={}: {}", outbox.getId(), e.getMessage());
            }
        }

        log.info("Startup recovery complete: dispatched {}/{} entries", dispatched, toRecover.size());
    }
}

