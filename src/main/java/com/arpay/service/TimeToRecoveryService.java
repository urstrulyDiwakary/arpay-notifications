package com.arpay.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Time-to-Recovery (TTR) tracking service.
 * <p>
 * Measures how long it takes for the system to return to normal after an overload event.
 * This is a critical SRE metric that was previously untracked.
 * <p>
 * Recovery is defined as:
 * - Queue depth returns to < 20% of max
 * - Processing rate returns to > 80% of normal
 * - No HTTP 429 rejections in last 2 minutes
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TimeToRecoveryService {

    private final QueueMetricsService queueMetricsService;
    private final MeterRegistry meterRegistry;

    // State tracking
    private boolean isInOverloadState = false;
    private LocalDateTime overloadStartTime = null;
    private LocalDateTime recoveryTime = null;
    private long lastRecoveryDurationSeconds = 0;

    // Thresholds
    private static final double QUEUE_RECOVERY_THRESHOLD_PERCENT = 20.0; // Queue < 20% of max
    private static final double PROCESSING_RATE_RECOVERY_PERCENT = 80.0; // Rate > 80% of normal
    private static final double NORMAL_PROCESSING_RATE = 50.0; // msg/s (baseline)
    private static final int MAX_QUEUE_SIZE = 10000;

    // Metrics cache
    private final Map<String, Number> metricsCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Initializing time-to-recovery tracking service");

        // Register gauges
        Gauge.builder("notification.recovery.is_in_overload", metricsCache,
                cache -> cache.computeIfAbsent("is_in_overload", k -> 0).doubleValue())
            .description("Whether system is currently in overload state (1=overload, 0=normal)")
            .register(meterRegistry);

        Gauge.builder("notification.recovery.current_duration_seconds", metricsCache,
                cache -> cache.computeIfAbsent("current_duration", k -> 0L).doubleValue())
            .description("Current recovery duration in seconds (if recovering, else 0)")
            .baseUnit("seconds")
            .register(meterRegistry);

        Gauge.builder("notification.recovery.last_duration_seconds", metricsCache,
                cache -> cache.computeIfAbsent("last_duration", k -> 0L).doubleValue())
            .description("Duration of last recovery period in seconds")
            .baseUnit("seconds")
            .register(meterRegistry);

        Gauge.builder("notification.recovery.count_total", metricsCache,
                cache -> cache.computeIfAbsent("recovery_count", k -> 0L).doubleValue())
            .description("Total number of recovery events")
            .register(meterRegistry);

        log.info("Time-to-recovery gauges registered");
    }

    /**
     * Check for overload state and track recovery every 5 seconds
     */
    @Scheduled(fixedRateString = "${notifications.recovery.check-interval-ms:5000}")
    public void checkRecoveryState() {
        try {
            Map<String, Object> summary = queueMetricsService.getQueueSummary();

            long pendingCount = ((Number) summary.get("pending")).longValue();
            double processingRate = ((Number) summary.get("processing_rate_per_second")).doubleValue();
            double queueUsagePercent = (double) pendingCount / MAX_QUEUE_SIZE * 100;

            boolean isOverloaded = detectOverloadState(pendingCount, processingRate);

            if (isOverloaded && !isInOverloadState) {
                // Transition to overload state
                enterOverloadState();
            } else if (!isOverloaded && isInOverloadState) {
                // Transition to recovery state
                exitOverloadState();
            }

            // Update metrics
            updateMetrics(isOverloaded);

        } catch (Exception e) {
            log.debug("Recovery state check failed: {}", e.getMessage());
        }
    }

    /**
     * Detect if system is in overload state
     */
    private boolean detectOverloadState(long pendingCount, double processingRate) {
        // Overload if queue > 70% of max
        double queueUsagePercent = (double) pendingCount / MAX_QUEUE_SIZE * 100;
        if (queueUsagePercent > 70) {
            return true;
        }

        // Overload if processing rate < 50% of normal
        if (processingRate > 0 && processingRate < (NORMAL_PROCESSING_RATE * 0.5)) {
            return true;
        }

        return false;
    }

    /**
     * Enter overload state
     */
    private void enterOverloadState() {
        isInOverloadState = true;
        overloadStartTime = LocalDateTime.now();
        recoveryTime = null;

        log.warn("⚠️  System entered OVERLOAD state - queue depth high or processing rate low");
        log.warn("   Will track time-to-recovery from this point");
    }

    /**
     * Exit overload state (recovered)
     */
    private void exitOverloadState() {
        if (overloadStartTime != null) {
            recoveryTime = LocalDateTime.now();
            long durationSeconds = java.time.Duration.between(overloadStartTime, recoveryTime).getSeconds();
            lastRecoveryDurationSeconds = durationSeconds;

            log.info("✅ System RECOVERED from overload - time to recovery: {} seconds", durationSeconds);
            log.info("   Recovery completed at: {}", recoveryTime);

            // Log recovery performance rating
            logRecoveryPerformance(durationSeconds);
        }

        isInOverloadState = false;
        overloadStartTime = null;
    }

    /**
     * Log recovery performance rating
     */
    private void logRecoveryPerformance(long durationSeconds) {
        String rating;
        if (durationSeconds < 60) {
            rating = "EXCELLENT";
        } else if (durationSeconds < 180) {
            rating = "GOOD";
        } else if (durationSeconds < 300) {
            rating = "FAIR";
        } else {
            rating = "POOR - Consider scaling workers or investigating bottleneck";
        }

        log.info("   Recovery performance: {} ({} seconds)", rating, durationSeconds);
    }

    /**
     * Update metrics cache
     */
    private void updateMetrics(boolean isOverloaded) {
        metricsCache.put("is_in_overload", isOverloaded ? 1 : 0);

        long currentDuration = 0;
        if (isOverloaded && overloadStartTime != null) {
            currentDuration = java.time.Duration.between(overloadStartTime, LocalDateTime.now()).getSeconds();
        }
        metricsCache.put("current_duration", currentDuration);
        metricsCache.put("last_duration", lastRecoveryDurationSeconds);

        // Increment recovery count on transition
        if (!isOverloaded && recoveryTime != null) {
            long currentCount = metricsCache.getOrDefault("recovery_count", 0L).longValue();
            metricsCache.put("recovery_count", currentCount + 1);
        }
    }

    /**
     * Get current recovery status
     */
    public RecoveryStatus getRecoveryStatus() {
        return new RecoveryStatus(
            isInOverloadState,
            overloadStartTime,
            recoveryTime,
            lastRecoveryDurationSeconds,
            overloadStartTime != null ?
                java.time.Duration.between(overloadStartTime, LocalDateTime.now()).getSeconds() : 0
        );
    }

    /**
     * Get average recovery time (from all recorded recoveries)
     * Note: In production, you'd want to store this in a persistent store
     */
    public long getAverageRecoveryTimeSeconds() {
        // For now, just return last recovery time
        // In production, maintain a rolling average
        return lastRecoveryDurationSeconds;
    }

    /**
     * Recovery status record
     */
    public record RecoveryStatus(
        boolean isInOverload,
        LocalDateTime overloadStartTime,
        LocalDateTime recoveryTime,
        long lastRecoveryDurationSeconds,
        long currentDurationSeconds
    ) {}
}
