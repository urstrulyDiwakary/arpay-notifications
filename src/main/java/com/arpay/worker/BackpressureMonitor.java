package com.arpay.worker;

import com.arpay.entity.OutboxStatus;
import com.arpay.filter.RateLimitFilter;
import com.arpay.repository.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically samples outbox queue depth and feeds a pressure ratio (0.0–1.0)
 * back to {@link RateLimitFilter} so that ingestion is throttled when the
 * processing pipeline is falling behind.
 *
 * <p>Pressure calculation:
 * <pre>
 *   ratio = min(1.0, (PENDING + QUEUED outbox entries) / maxQueueDepth)
 * </pre>
 * The rate limiter's global cap is then scaled down linearly:
 * <pre>
 *   effectiveRate = baseRate × max(0.1, 1.0 − ratio)
 * </pre>
 * At 70 % queue fill the global rate is reduced to 30 % of its nominal value;
 * at 100 % fill it floor-clamps at 10 % (system stays responsive but rejects
 * most incoming load).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackpressureMonitor {

    private final NotificationOutboxRepository outboxRepository;
    private final RateLimitFilter rateLimitFilter;

    @Value("${notifications.worker.backpressure.max-queue-depth:10000}")
    private int maxQueueDepth;

    @Value("${notifications.backpressure.check-interval-ms:5000}")
    private String checkIntervalMs; // unused — just documents the property

    @Scheduled(fixedRateString = "${notifications.backpressure.check-interval-ms:5000}")
    public void checkAndAdjust() {
        try {
            long pending = outboxRepository.countByStatus(OutboxStatus.PENDING)
                         + outboxRepository.countByStatus(OutboxStatus.QUEUED);

            double ratio = Math.min(1.0, (double) pending / maxQueueDepth);

            if (ratio > 0.5) {
                log.warn("Backpressure: outbox depth={}, pressure={:.2f} — throttling ingestion", pending, ratio);
            }

            rateLimitFilter.adjustForBackpressure(ratio);

        } catch (Exception e) {
            // Fail open: if we can't measure pressure, don't throttle
            log.debug("Backpressure check failed (fail-open): {}", e.getMessage());
            rateLimitFilter.adjustForBackpressure(0.0);
        }
    }
}

