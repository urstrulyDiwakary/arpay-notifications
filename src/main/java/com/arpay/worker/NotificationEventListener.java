package com.arpay.worker;

import com.arpay.entity.NotificationOutbox;
import com.arpay.entity.OutboxStatus;
import com.arpay.event.NotificationCreatedEvent;
import com.arpay.repository.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Transactional event listener that bridges notification creation to async delivery.
 *
 * <p>The flow is:
 * <ol>
 *   <li>NotificationServiceImpl creates Notification + NotificationOutbox atomically.</li>
 *   <li>NotificationCreatedEvent is published inside the transaction.</li>
 *   <li>After commit, this listener marks the outbox entry as QUEUED and dispatches
 *       it to {@link NotificationWorker} for actual FCM delivery.</li>
 * </ol>
 *
 * <p>Using AFTER_COMMIT guarantees the outbox row is visible in the DB before the
 * worker tries to read it (avoids phantom-read style races).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationWorker notificationWorker;

    /**
     * Invoked after the creating transaction commits. Runs in a separate thread
     * (due to {@code @Async}) so it never delays the HTTP response.
     */
    @Async("normalNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleNotificationCreated(NotificationCreatedEvent event) {
        com.arpay.entity.Notification notification = event.getNotification();
        log.info("Handling NotificationCreatedEvent: eventId={}", notification.getNotificationEventId());

        try {
            java.util.Optional<NotificationOutbox> outboxOpt =
                outboxRepository.findByNotificationEventId(notification.getNotificationEventId());

            if (outboxOpt.isEmpty()) {
                log.warn("Outbox entry not found for eventId={} — skipping dispatch",
                         notification.getNotificationEventId());
                return;
            }

            NotificationOutbox outbox = outboxOpt.get();

            // Skip entries that are already in-flight or completed
            if (outbox.getStatus() == OutboxStatus.QUEUED ||
                outbox.getStatus() == OutboxStatus.PUBLISHED) {
                log.debug("Outbox already queued/published for eventId={} — skipping",
                          notification.getNotificationEventId());
                return;
            }

            // Mark as QUEUED before dispatching so startup recovery can detect
            // entries that were handed off but whose workers crashed mid-delivery.
            outbox.markQueued();
            outboxRepository.save(outbox);

            // Dispatch to async worker (@Async — returns immediately)
            notificationWorker.processOutboxEntry(outbox.getId());

            log.info("Outbox entry queued and worker dispatched: eventId={}, outboxId={}",
                     notification.getNotificationEventId(), outbox.getId());

        } catch (Exception e) {
            log.error("Failed to handle NotificationCreatedEvent for eventId={}",
                      notification.getNotificationEventId(), e);
        }
    }
}

