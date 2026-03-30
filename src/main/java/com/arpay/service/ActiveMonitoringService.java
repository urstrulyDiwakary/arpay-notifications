package com.arpay.service;

import com.arpay.repository.DeliveryIdempotencyKeyRepository;
import com.arpay.repository.NotificationDlqEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Active monitoring service - turns passive dashboard into active alerting.
 * 
 * Runs every 5 minutes and alerts on:
 * - Low success rate (< 90%)
 * - High DLQ size (> 50)
 * - High failure spike
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActiveMonitoringService {
    
    private final DeliveryIdempotencyKeyRepository idempotencyKeyRepository;
    private final NotificationDlqEntryRepository dlqRepository;
    private final AlertService alertService;
    
    @Value("${monitoring.alert.success-rate-threshold:90.0}")
    private double successRateThreshold;
    
    @Value("${monitoring.alert.dlq-size-threshold:50}")
    private int dlqSizeThreshold;
    
    @Value("${monitoring.alert.enabled:true}")
    private boolean alertingEnabled;
    
    /**
     * Check health every 5 minutes and alert if thresholds exceeded
     */
    @Scheduled(fixedRateString = "${monitoring.check.interval-ms:300000}")
    public void checkHealthAndAlert() {
        if (!alertingEnabled) {
            log.debug("Active monitoring is disabled");
            return;
        }
        
        log.info("Running active health check");
        
        Map<String, Object> alerts = new HashMap<>();
        
        // Check 1: Success rate
        double successRate = calculateSuccessRate();
        if (successRate < successRateThreshold) {
            String message = String.format(
                "CRITICAL: Notification success rate is %.1f%% (threshold: %.1f%%)",
                successRate, successRateThreshold
            );
            alerts.put("successRate", message);
            log.error(message);
        }
        
        // Check 2: DLQ size
        long dlqSize = dlqRepository.countByResolvedFalse();
        if (dlqSize > dlqSizeThreshold) {
            String message = String.format(
                "WARNING: DLQ size is %d (threshold: %d)",
                dlqSize, dlqSizeThreshold
            );
            alerts.put("dlqSize", message);
            log.warn(message);
        }
        
        // Check 3: Recent failure spike (last 15 minutes)
        long recentFailures = idempotencyKeyRepository.countRecentFailures(
            java.time.LocalDateTime.now().minusMinutes(15)
        );
        if (recentFailures > 50) {
            String message = String.format(
                "CRITICAL: %d failures in last 15 minutes",
                recentFailures
            );
            alerts.put("failureSpike", message);
            log.error(message);
        }
        
        // Send alerts if any
        if (!alerts.isEmpty()) {
            alertService.sendAlert("Notification Health Check", alerts);
        } else {
            log.info("Health check passed - all metrics normal");
        }
    }
    
    /**
     * Calculate success rate for last hour
     */
    private double calculateSuccessRate() {
        long sent = idempotencyKeyRepository.countByStatus(
            com.arpay.entity.DeliveryIdempotencyKey.DeliveryStatus.SENT
        );
        long failed = idempotencyKeyRepository.countByStatus(
            com.arpay.entity.DeliveryIdempotencyKey.DeliveryStatus.FAILED
        );
        long permanentFailures = idempotencyKeyRepository.countByStatus(
            com.arpay.entity.DeliveryIdempotencyKey.DeliveryStatus.PERMANENT_FAILURE
        );
        
        long total = sent + failed + permanentFailures;
        if (total == 0) {
            return 100.0;
        }
        
        return (double) sent / total * 100;
    }
}
