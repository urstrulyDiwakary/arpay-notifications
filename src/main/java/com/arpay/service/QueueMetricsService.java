package com.arpay.service;

import com.arpay.entity.OutboxStatus;
import com.arpay.repository.NotificationDeliveryLogRepository;
import com.arpay.repository.NotificationDlqEntryRepository;
import com.arpay.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comprehensive queue monitoring service exposing metrics for Prometheus.
 * <p>
 * Tracks:
 * - Queue depths (PENDING, QUEUED, PROCESSING per priority)
 * - Processing rates (messages/sec, messages/min)
 * - DLQ metrics
 * - Worker throughput
 * - Queue aging (how long messages wait before processing)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueueMetricsService {

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationDlqEntryRepository dlqRepository;
    private final NotificationDeliveryLogRepository deliveryLogRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    // Metrics state
    private final Map<String, Number> metricsCache = new ConcurrentHashMap<>();

    // Processing rate tracking
    private long lastProcessedCount = 0;
    private LocalDateTime lastCheckTime = LocalDateTime.now();
    private double processingRatePerSecond = 0.0;
    private double processingRatePerMinute = 0.0;

    // Queue names
    private static final String CRITICAL_QUEUE = "notification:queue:critical";
    private static final String NORMAL_QUEUE = "notification:queue:normal";
    private static final String LOW_QUEUE = "notification:queue:low";
    private static final String DLQ = "notification:dlq";

    @PostConstruct
    public void init() {
        log.info("Initializing queue metrics service");

        // Register gauges for real-time metrics
        Gauge.builder("notification.queue.pending.count", metricsCache,
                cache -> cache.computeIfAbsent("pending", k -> 0L).doubleValue())
            .description("Number of pending notifications waiting to be processed")
            .baseUnit("messages")
            .register(meterRegistry);

        Gauge.builder("notification.queue.queued.count", metricsCache,
                cache -> cache.computeIfAbsent("queued", k -> 0L).doubleValue())
            .description("Number of notifications queued for processing (claimed by workers)")
            .baseUnit("messages")
            .register(meterRegistry);

        Gauge.builder("notification.queue.processing.count", metricsCache,
                cache -> cache.computeIfAbsent("processing", k -> 0L).doubleValue())
            .description("Number of notifications currently being processed")
            .baseUnit("messages")
            .register(meterRegistry);

        Gauge.builder("notification.queue.critical.count", metricsCache,
                cache -> cache.computeIfAbsent("critical", k -> 0L).doubleValue())
            .description("Number of critical priority notifications in queue")
            .baseUnit("messages")
            .register(meterRegistry);

        Gauge.builder("notification.queue.normal.count", metricsCache,
                cache -> cache.computeIfAbsent("normal", k -> 0L).doubleValue())
            .description("Number of normal priority notifications in queue")
            .baseUnit("messages")
            .register(meterRegistry);

        Gauge.builder("notification.queue.low.count", metricsCache,
                cache -> cache.computeIfAbsent("low", k -> 0L).doubleValue())
            .description("Number of low priority notifications in queue")
            .baseUnit("messages")
            .register(meterRegistry);

        Gauge.builder("notification.dlq.count", metricsCache,
                cache -> cache.computeIfAbsent("dlq", k -> 0L).doubleValue())
            .description("Number of failed notifications in dead letter queue")
            .baseUnit("messages")
            .register(meterRegistry);

        Gauge.builder("notification.processing.rate.per_second", metricsCache,
                cache -> cache.computeIfAbsent("rate_per_second", k -> 0.0).doubleValue())
            .description("Notification processing rate (messages per second)")
            .baseUnit("messages/sec")
            .register(meterRegistry);

        Gauge.builder("notification.processing.rate.per_minute", metricsCache,
                cache -> cache.computeIfAbsent("rate_per_minute", k -> 0.0).doubleValue())
            .description("Notification processing rate (messages per minute)")
            .baseUnit("messages/min")
            .register(meterRegistry);

        Gauge.builder("notification.queue.avg_age_seconds", metricsCache,
                cache -> cache.computeIfAbsent("avg_age_seconds", k -> 0L).doubleValue())
            .description("Average age of pending notifications in seconds")
            .baseUnit("seconds")
            .register(meterRegistry);

        Gauge.builder("notification.firebase.circuit_breaker.state", metricsCache,
                cache -> cache.computeIfAbsent("circuit_breaker_state", k -> 0).doubleValue())
            .description("Firebase circuit breaker state (0=closed, 1=open, 2=half-open)")
            .register(meterRegistry);

        // Start periodic metrics collection
        log.info("Queue metrics gauges registered");
    }

    /**
     * Collect queue metrics every 5 seconds
     */
    @Scheduled(fixedRateString = "${notifications.metrics.collection-interval-ms:5000}")
    public void collectMetrics() {
        try {
            collectQueueDepths();
            collectProcessingRates();
            collectQueueAging();
            collectCircuitBreakerState();
        } catch (Exception e) {
            log.debug("Metrics collection failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Collect queue depth metrics by status and priority
     */
    private void collectQueueDepths() {
        // Database queue depths
        long pending = outboxRepository.countByStatus(OutboxStatus.PENDING);
        long queued = outboxRepository.countByStatus(OutboxStatus.QUEUED);
        // Note: PROCESSING status doesn't exist, use QUEUED for in-progress
        long processing = outboxRepository.countByStatus(OutboxStatus.QUEUED);

        metricsCache.put("pending", pending);
        metricsCache.put("queued", queued);
        metricsCache.put("processing", processing);

        // Redis stream depths (more real-time)
        try {
            Long criticalSize = redisTemplate.opsForStream().size(CRITICAL_QUEUE);
            Long normalSize = redisTemplate.opsForStream().size(NORMAL_QUEUE);
            Long lowSize = redisTemplate.opsForStream().size(LOW_QUEUE);
            Long dlqSize = redisTemplate.opsForStream().size(DLQ);

            metricsCache.put("critical", criticalSize != null ? criticalSize : 0L);
            metricsCache.put("normal", normalSize != null ? normalSize : 0L);
            metricsCache.put("low", lowSize != null ? lowSize : 0L);
            metricsCache.put("dlq", dlqSize != null ? dlqSize : 0L);
        } catch (Exception e) {
            log.debug("Failed to read Redis queue sizes: {}", e.getMessage());
            metricsCache.put("critical", 0L);
            metricsCache.put("normal", 0L);
            metricsCache.put("low", 0L);
            metricsCache.put("dlq", 0L);
        }

        // Also expose DLQ count from database
        long dbDlqCount = dlqRepository.countByResolvedFalse();
        metricsCache.put("dlq_db", dbDlqCount);
    }

    /**
     * Calculate processing rates based on delivery logs
     */
    private void collectProcessingRates() {
        LocalDateTime now = LocalDateTime.now();
        Duration elapsed = Duration.between(lastCheckTime, now);

        if (elapsed.getSeconds() < 1) {
            return; // Too soon to calculate rate
        }

        try {
            // Count deliveries in last minute
            long recentDeliveries = deliveryLogRepository.countByProcessedAtAfter(
                now.minusMinutes(1)
            );

            // Calculate rates
            processingRatePerMinute = recentDeliveries;
            processingRatePerSecond = recentDeliveries / 60.0;

            metricsCache.put("rate_per_second", processingRatePerSecond);
            metricsCache.put("rate_per_minute", processingRatePerMinute);

            lastCheckTime = now;
            lastProcessedCount = recentDeliveries;

        } catch (Exception e) {
            log.debug("Failed to calculate processing rate: {}", e.getMessage());
        }
    }

    /**
     * Calculate average age of pending notifications
     */
    private void collectQueueAging() {
        try {
            // Get average age of pending notifications
            LocalDateTime threshold = LocalDateTime.now().minusHours(1);
            long pendingCount = outboxRepository.countByStatus(OutboxStatus.PENDING);

            if (pendingCount == 0) {
                metricsCache.put("avg_age_seconds", 0L);
                return;
            }

            // Calculate average age (simplified - in production, use SQL AVG)
            // This is a rough estimate based on creation time distribution
            long stuckCount = outboxRepository.findPendingWithLimit(
                OutboxStatus.PENDING.name(), 100
            ).stream()
            .filter(o -> o.getCreatedAt().isBefore(threshold))
            .count();

            long avgAgeSeconds = (stuckCount * 3600) / Math.max(1, pendingCount);
            metricsCache.put("avg_age_seconds", avgAgeSeconds);

        } catch (Exception e) {
            log.debug("Failed to calculate queue aging: {}", e.getMessage());
            metricsCache.put("avg_age_seconds", 0L);
        }
    }

    /**
     * Track Firebase circuit breaker state
     * This is a placeholder - actual implementation would integrate with circuit breaker library
     */
    private void collectCircuitBreakerState() {
        // Placeholder: In production, integrate with Resilience4j or similar
        // 0 = CLOSED (healthy), 1 = OPEN (failing), 2 = HALF_OPEN (recovering)
        metricsCache.put("circuit_breaker_state", 0);
    }

    /**
     * Get current queue summary for API responses
     */
    public Map<String, Object> getQueueSummary() {
        Map<String, Object> summary = new ConcurrentHashMap<>();
        summary.put("pending", metricsCache.getOrDefault("pending", 0L));
        summary.put("queued", metricsCache.getOrDefault("queued", 0L));
        summary.put("processing", metricsCache.getOrDefault("processing", 0L));
        summary.put("critical", metricsCache.getOrDefault("critical", 0L));
        summary.put("normal", metricsCache.getOrDefault("normal", 0L));
        summary.put("low", metricsCache.getOrDefault("low", 0L));
        summary.put("dlq", metricsCache.getOrDefault("dlq", 0L));
        summary.put("dlq_db", metricsCache.getOrDefault("dlq_db", 0L));
        summary.put("processing_rate_per_second", metricsCache.getOrDefault("rate_per_second", 0.0));
        summary.put("processing_rate_per_minute", metricsCache.getOrDefault("rate_per_minute", 0.0));
        summary.put("avg_age_seconds", metricsCache.getOrDefault("avg_age_seconds", 0L));
        summary.put("timestamp", LocalDateTime.now().toString());
        return summary;
    }
}
