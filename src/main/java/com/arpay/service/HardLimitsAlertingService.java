package com.arpay.service;

import com.arpay.entity.OutboxStatus;
import com.arpay.repository.NotificationDeliveryLogRepository;
import com.arpay.repository.NotificationDlqEntryRepository;
import com.arpay.repository.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Comprehensive alerting service that monitors hard limits and triggers alerts.
 * <p>
 * Monitors:
 * - DLQ size threshold
 * - Queue age (average and max)
 * - Processing lag (rate below threshold)
 * - Queue depth approaching hard limits
 * - Success rate degradation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HardLimitsAlertingService {

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationDlqEntryRepository dlqRepository;
    private final NotificationDeliveryLogRepository deliveryLogRepository;
    private final QueueMetricsService queueMetricsService;
    private final AlertService alertService;

    // Hard limits from configuration
    @Value("${notifications.system.max-queue-size:10000}")
    private int maxQueueSize;

    @Value("${notifications.system.max-dlq-size:100}")
    private int maxDlqSize;

    @Value("${notifications.system.max-dlq-size-hard-stop:200}")
    private int maxDlqSizeHardStop;

    @Value("${notifications.system.max-queue-age-seconds:300}")
    private int maxQueueAgeSeconds;

    @Value("${notifications.system.min-processing-rate:20}")
    private double minProcessingRate;

    @Value("${notifications.system.backpressure-threshold-percent:70}")
    private int backpressureThresholdPercent;

    @Value("${notifications.system.ingestion-pause-threshold-percent:90}")
    private int ingestionPauseThresholdPercent;

    @Value("${monitoring.alert.success-rate-threshold:90.0}")
    private double successRateThreshold;

    @Value("${alerting.enabled:true}")
    private boolean alertingEnabled;

    // Alert cooldown tracking (prevent alert spam)
    private LocalDateTime lastDlqAlertTime = LocalDateTime.MIN;
    private LocalDateTime lastQueueAgeAlertTime = LocalDateTime.MIN;
    private LocalDateTime lastProcessingRateAlertTime = LocalDateTime.MIN;
    private LocalDateTime lastQueueDepthAlertTime = LocalDateTime.MIN;

    private static final long ALERT_COOLDOWN_MS = 300000; // 5 minutes

    @PostConstruct
    public void init() {
        log.info("Hard limits alerting service initialized");
        log.info("  - Max queue size: {}", maxQueueSize);
        log.info("  - Max DLQ size: {} (hard stop: {})", maxDlqSize, maxDlqSizeHardStop);
        log.info("  - Max queue age: {} seconds", maxQueueAgeSeconds);
        log.info("  - Min processing rate: {} msg/s", minProcessingRate);
    }

    /**
     * Monitor all hard limits every 30 seconds
     */
    @Scheduled(fixedRateString = "${notifications.alerting.check-interval-ms:30000}")
    public void monitorHardLimits() {
        if (!alertingEnabled) {
            log.debug("Alerting disabled");
            return;
        }

        try {
            checkDlqSize();
            checkQueueAge();
            checkProcessingRate();
            checkQueueDepth();
            checkSuccessRate();
        } catch (Exception e) {
            log.error("Hard limits monitoring failed: {}", e.getMessage());
        }
    }

    /**
     * Check DLQ size against thresholds
     */
    private void checkDlqSize() {
        long dlqSize = dlqRepository.countByResolvedFalse();

        // Hard stop threshold
        if (dlqSize >= maxDlqSizeHardStop) {
            if (canSendAlert(lastDlqAlertTime)) {
                Map<String, Object> alerts = new HashMap<>();
                alerts.put("dlq_size", dlqSize);
                alerts.put("threshold", maxDlqSizeHardStop);
                alerts.put("action", "HARD STOP - Ingestion paused until DLQ reduced");
                alerts.put("timestamp", LocalDateTime.now().toString());

                alertService.sendAlert("CRITICAL: DLQ Size Exceeded Hard Stop Limit", alerts);
                lastDlqAlertTime = LocalDateTime.now();
            }
        }
        // Warning threshold
        else if (dlqSize >= maxDlqSize) {
            if (canSendAlert(lastDlqAlertTime)) {
                Map<String, Object> alerts = new HashMap<>();
                alerts.put("dlq_size", dlqSize);
                alerts.put("threshold", maxDlqSize);
                alerts.put("hard_stop_threshold", maxDlqSizeHardStop);
                alerts.put("action", "Investigate failures immediately");
                alerts.put("timestamp", LocalDateTime.now().toString());

                alertService.sendAlert("WARNING: DLQ Size Exceeded Threshold", alerts);
                lastDlqAlertTime = LocalDateTime.now();
            }
        }
    }

    /**
     * Check queue age against thresholds
     */
    private void checkQueueAge() {
        try {
            // Get oldest pending notification
            var pendingList = outboxRepository.findPendingWithLimit(OutboxStatus.PENDING.name(), 1);
            if (pendingList.isEmpty()) {
                return; // No pending notifications
            }

            var oldestPending = pendingList.get(0);
            LocalDateTime createdAt = oldestPending.getCreatedAt();
            long ageSeconds = java.time.Duration.between(createdAt, LocalDateTime.now()).getSeconds();

            // Critical: exceeds max age
            if (ageSeconds >= maxQueueAgeSeconds) {
                if (canSendAlert(lastQueueAgeAlertTime)) {
                    Map<String, Object> alerts = new HashMap<>();
                    alerts.put("oldest_message_age_seconds", ageSeconds);
                    alerts.put("threshold", maxQueueAgeSeconds);
                    alerts.put("notification_id", oldestPending.getNotificationEventId().toString());
                    alerts.put("created_at", createdAt.toString());
                    alerts.put("action", "Scale workers or investigate processing bottleneck");
                    alerts.put("timestamp", LocalDateTime.now().toString());

                    alertService.sendAlert("CRITICAL: Queue Age Exceeded Maximum", alerts);
                    lastQueueAgeAlertTime = LocalDateTime.now();
                }
            }
            // Warning: approaching max age
            else if (ageSeconds >= maxQueueAgeSeconds * 0.6) {
                if (canSendAlert(lastQueueAgeAlertTime)) {
                    Map<String, Object> alerts = new HashMap<>();
                    alerts.put("oldest_message_age_seconds", ageSeconds);
                    alerts.put("threshold", maxQueueAgeSeconds);
                    alerts.put("action", "Monitor closely - queue aging detected");
                    alerts.put("timestamp", LocalDateTime.now().toString());

                    alertService.sendAlert("WARNING: Queue Age Approaching Maximum", alerts);
                    lastQueueAgeAlertTime = LocalDateTime.now();
                }
            }
        } catch (Exception e) {
            log.debug("Queue age check failed: {}", e.getMessage());
        }
    }

    /**
     * Check processing rate against minimum threshold
     */
    private void checkProcessingRate() {
        try {
            Map<String, Object> summary = queueMetricsService.getQueueSummary();
            double ratePerSecond = ((Number) summary.get("processing_rate_per_second")).doubleValue();

            // Critical: below minimum processing rate
            if (ratePerSecond < minProcessingRate && ratePerSecond > 0) {
                if (canSendAlert(lastProcessingRateAlertTime)) {
                    Map<String, Object> alerts = new HashMap<>();
                    alerts.put("current_rate", String.format("%.2f", ratePerSecond));
                    alerts.put("threshold", minProcessingRate);
                    alerts.put("queue_depth", summary.get("pending"));
                    alerts.put("action", "Check Firebase latency, worker threads, database connections");
                    alerts.put("timestamp", LocalDateTime.now().toString());

                    alertService.sendAlert("CRITICAL: Processing Rate Below Minimum", alerts);
                    lastProcessingRateAlertTime = LocalDateTime.now();
                }
            }
            // Warning: processing rate is low but not critical
            else if (ratePerSecond < minProcessingRate * 1.5 && ratePerSecond > 0) {
                if (canSendAlert(lastProcessingRateAlertTime)) {
                    Map<String, Object> alerts = new HashMap<>();
                    alerts.put("current_rate", String.format("%.2f", ratePerSecond));
                    alerts.put("threshold", minProcessingRate);
                    alerts.put("action", "Monitor processing throughput");
                    alerts.put("timestamp", LocalDateTime.now().toString());

                    alertService.sendAlert("WARNING: Processing Rate Below Optimal", alerts);
                    lastProcessingRateAlertTime = LocalDateTime.now();
                }
            }
        } catch (Exception e) {
            log.debug("Processing rate check failed: {}", e.getMessage());
        }
    }

    /**
     * Check queue depth against backpressure thresholds
     */
    private void checkQueueDepth() {
        try {
            long pendingCount = outboxRepository.countByStatus(OutboxStatus.PENDING);
            double usagePercent = (double) pendingCount / maxQueueSize * 100;

            // Critical: approaching hard limit
            if (usagePercent >= ingestionPauseThresholdPercent) {
                if (canSendAlert(lastQueueDepthAlertTime)) {
                    Map<String, Object> alerts = new HashMap<>();
                    alerts.put("queue_depth", pendingCount);
                    alerts.put("max_queue_size", maxQueueSize);
                    alerts.put("usage_percent", String.format("%.1f", usagePercent));
                    alerts.put("action", "Ingestion throttling active - HTTP 429 being returned");
                    alerts.put("timestamp", LocalDateTime.now().toString());

                    alertService.sendAlert("CRITICAL: Queue Depth Near Hard Limit", alerts);
                    lastQueueDepthAlertTime = LocalDateTime.now();
                }
            }
            // Warning: backpressure threshold
            else if (usagePercent >= backpressureThresholdPercent) {
                if (canSendAlert(lastQueueDepthAlertTime)) {
                    Map<String, Object> alerts = new HashMap<>();
                    alerts.put("queue_depth", pendingCount);
                    alerts.put("max_queue_size", maxQueueSize);
                    alerts.put("usage_percent", String.format("%.1f", usagePercent));
                    alerts.put("action", "Backpressure throttling active - rate limiting increased");
                    alerts.put("timestamp", LocalDateTime.now().toString());

                    alertService.sendAlert("WARNING: Queue Depth Exceeded Backpressure Threshold", alerts);
                    lastQueueDepthAlertTime = LocalDateTime.now();
                }
            }
        } catch (Exception e) {
            log.debug("Queue depth check failed: {}", e.getMessage());
        }
    }

    /**
     * Check success rate against threshold
     */
    private void checkSuccessRate() {
        try {
            long sent = deliveryLogRepository.countByChannelAndStatus(
                com.arpay.entity.NotificationDeliveryLog.Channel.PUSH,
                com.arpay.entity.NotificationDeliveryLog.Status.SENT
            );

            long failed = deliveryLogRepository.countByChannelAndStatus(
                com.arpay.entity.NotificationDeliveryLog.Channel.PUSH,
                com.arpay.entity.NotificationDeliveryLog.Status.FAILED
            );

            long total = sent + failed;
            if (total == 0) {
                return; // No data
            }

            double successRate = (double) sent / total * 100;

            if (successRate < successRateThreshold) {
                Map<String, Object> alerts = new HashMap<>();
                alerts.put("success_rate", String.format("%.2f%%", successRate));
                alerts.put("threshold", successRateThreshold + "%");
                alerts.put("sent", sent);
                alerts.put("failed", failed);
                alerts.put("total", total);
                alerts.put("action", "Investigate Firebase errors, check circuit breaker status");
                alerts.put("timestamp", LocalDateTime.now().toString());

                alertService.sendAlert("WARNING: Success Rate Below Threshold", alerts);
            }
        } catch (Exception e) {
            log.debug("Success rate check failed: {}", e.getMessage());
        }
    }

    /**
     * Check if enough time has passed since last alert (cooldown)
     */
    private boolean canSendAlert(LocalDateTime lastAlertTime) {
        long elapsedMs = java.time.Duration.between(lastAlertTime, LocalDateTime.now()).toMillis();
        return elapsedMs >= ALERT_COOLDOWN_MS;
    }
}
