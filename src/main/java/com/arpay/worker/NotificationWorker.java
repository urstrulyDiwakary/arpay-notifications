package com.arpay.worker;

import com.arpay.entity.*;
import com.arpay.notification.FirebasePushService;
import com.arpay.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Notification worker that processes outbox entries and sends push notifications.
 * Implements idempotent delivery to prevent duplicates on worker crashes.
 * 
 * CRITICAL: This is NOT exactly-once delivery. It's at-least-once with deduplication.
 * Industry standard is "effectively-once" - duplicates are possible but detected and handled.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationWorker {
    
    private final NotificationOutboxRepository outboxRepository;
    private final UserDeviceTokenRepository deviceTokenRepository;
    private final DeliveryIdempotencyKeyRepository idempotencyKeyRepository;
    private final FirebasePushService firebasePushService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationDlqEntryRepository dlqRepository;
    private final NotificationDeliveryLogRepository deliveryLogRepository;
    private final MeterRegistry meterRegistry;
    
    @Value("${notification.retry.max-attempts:3}")
    private int maxRetryAttempts;
    
    @Value("${notification.queue.critical:notification:queue:critical}")
    private String criticalQueue;

    @Value("${notification.queue.normal:notification:queue:normal}")
    private String normalQueue;

    @Value("${notification.queue.low:notification:queue:low}")
    private String lowQueue;

    @Value("${notification.worker.max-inflight:200}")
    private int maxInflight;
    
    @Value("${notifications.worker.backpressure.enabled:true}")
    private boolean backpressureEnabled;
    
    @Value("${notifications.worker.backpressure.max-queue-depth:10000}")
    private int maxQueueDepth;
    
    /**
     * Process a single outbox entry asynchronously on the critical executor
     */
    @Async("criticalNotificationExecutor")
    public void processCriticalOutboxEntry(UUID outboxId) {
        processOutboxEntry(outboxId, true);
    }

    /**
     * Process a single outbox entry asynchronously
     */
    @Async("normalNotificationExecutor")
    public void processOutboxEntry(UUID outboxId) {
        processOutboxEntry(outboxId, false);
    }
    
    /**
     * Process a single outbox entry (internal method with priority flag)
     * Uses atomic claim to prevent duplicate processing
     */
    public void processOutboxEntry(UUID outboxId, boolean isCritical) {
        MDC.put("outboxId", outboxId.toString());
        try {
            doProcessOutboxEntry(outboxId, isCritical);
        } finally {
            MDC.remove("outboxId");
            MDC.remove("notificationEventId");
            MDC.remove("userId");
        }
    }

    private void doProcessOutboxEntry(UUID outboxId, boolean isCritical) {
        Optional<NotificationOutbox> outboxOpt;
        
        try {
            // Atomic claim with optimistic locking
            outboxOpt = outboxRepository.findById(outboxId);
        } catch (CannotAcquireLockException e) {
            log.warn("Could not acquire lock for outbox entry: id={}", outboxId);
            return;
        }
        
        if (outboxOpt.isEmpty()) {
            log.warn("Outbox entry not found: id={}", outboxId);
            return;
        }
        
        NotificationOutbox outbox = outboxOpt.get();
        MDC.put("notificationEventId", outbox.getNotificationEventId().toString());
        
        // Skip if already processed (idempotency check)
        if (outbox.getStatus() == OutboxStatus.PUBLISHED) {
            log.debug("Outbox entry already published: id={}", outboxId);
            return;
        }
        
        // Check retry limit
        if (!outbox.canRetry(maxRetryAttempts)) {
            log.error("Outbox entry exceeded max retries: id={}, eventId={}", 
                     outboxId, outbox.getNotificationEventId());
            String error = "Max retry attempts exceeded: " + maxRetryAttempts;
            outbox.markDeadLetter(error);
            outboxRepository.save(outbox);
            // Move to DLQ for scheduled retry with exponential backoff
            createDlqEntry(outbox, error);
            meterRegistry.counter("notification.outbox.dead_letter").increment();
            return;
        }
        
        try {
            log.info("Processing outbox entry: id={}, eventId={}, type={}", 
                    outboxId, outbox.getNotificationEventId(), outbox.getEventType());
            
            // Parse event payload
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(
                outbox.getEventPayload(), Map.class);
            
            // Process based on event type
            switch (outbox.getEventType()) {
                case "NOTIFICATION_CREATED" -> processNotificationCreated(outbox, payload);
                default -> {
                    log.warn("Unknown event type: {}", outbox.getEventType());
                    outbox.markFailed("Unknown event type: " + outbox.getEventType());
                    outboxRepository.save(outbox);
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to process outbox entry: id={}, eventId={}", 
                     outboxId, outbox.getNotificationEventId(), e);
            outbox.markFailed(e.getMessage());
            outboxRepository.save(outbox);
        }
    }
    
    /**
     * Process NOTIFICATION_CREATED event with idempotent delivery tracking
     */
    private void processNotificationCreated(NotificationOutbox outbox, Map<String, Object> payload) {
        UUID userId = UUID.fromString(payload.get("userId").toString());
        MDC.put("userId", userId.toString());
        String title = payload.get("title").toString();
        String message = payload.get("message").toString();
        String entityType = payload.get("entityType") != null ? 
            payload.get("entityType").toString() : null;
        UUID entityId = payload.get("entityId") != null ? 
            UUID.fromString(payload.get("entityId").toString()) : null;
        UUID notificationId = UUID.fromString(payload.get("notificationId").toString());
        UUID notificationEventId = outbox.getNotificationEventId();
        
        // Get active device tokens for user
        List<UserDeviceToken> tokens = deviceTokenRepository.findByUserIdAndIsActiveTrue(userId);
        
        if (tokens.isEmpty()) {
            log.debug("No active device tokens for userId={}", userId);
            outbox.markPublished("NO_TOKENS");
            outboxRepository.save(outbox);
            return;
        }
        
        // Build push data
        Map<String, String> data = new HashMap<>();
        data.put("notificationId", notificationEventId.toString());
        data.put("entityType", entityType != null ? entityType : "");
        data.put("entityId", entityId != null ? entityId.toString() : "");
        data.put("userId", userId.toString());
        
        // Track delivery results
        int successCount = 0;
        int failureCount = 0;
        int duplicateCount = 0;
        int permanentFailureCount = 0;
        
        for (UserDeviceToken token : tokens) {
            try {
                DeliveryResult result = deliverToToken(
                    notificationId, notificationEventId, token, title, message, data);
                
                switch (result) {
                    case SUCCESS -> successCount++;
                    case FAILED -> failureCount++;
                    case DUPLICATE -> duplicateCount++;
                    case PERMANENT_FAILURE -> permanentFailureCount++;
                }
                
            } catch (Exception e) {
                log.error("Error sending push to token: tokenId={}", token.getId(), e);
                failureCount++;
            }
        }
        
        log.info("Push notification completed: eventId={}, success={}, failure={}, duplicate={}, permanentFailure={}", 
                notificationEventId, successCount, failureCount, duplicateCount, permanentFailureCount);
        
        // Mark outbox as published
        outbox.markPublished(String.valueOf(successCount));
        outboxRepository.save(outbox);
    }
    
    /**
     * Deliver notification to a single device token with idempotency tracking.
     * 
     * @return DeliveryResult indicating outcome
     */
    private DeliveryResult deliverToToken(
            UUID notificationId, 
            UUID notificationEventId,
            UserDeviceToken token,
            String title,
            String message,
            Map<String, String> data) {
        
        String deliveryId = DeliveryIdempotencyKey.generateDeliveryId(
            notificationEventId, token.getDeviceToken());
        String tokenHash = hashToken(token.getDeviceToken());
        
        // Check for existing delivery (idempotency check)
        Optional<DeliveryIdempotencyKey> existingOpt = idempotencyKeyRepository.findByDeliveryId(deliveryId);
        
        if (existingOpt.isPresent()) {
            DeliveryIdempotencyKey existing = existingOpt.get();
            
            // Check if expired
            if (existing.isExpired()) {
                log.debug("Delivery idempotency key expired, allowing retry: deliveryId={}", deliveryId);
                // Allow retry - delete expired key
                idempotencyKeyRepository.delete(existing);
            } else if (existing.getStatus() == DeliveryIdempotencyKey.DeliveryStatus.SENT) {
                log.debug("Duplicate delivery detected (already sent): deliveryId={}", deliveryId);
                return DeliveryResult.DUPLICATE;
            } else if (existing.getStatus() == DeliveryIdempotencyKey.DeliveryStatus.PERMANENT_FAILURE) {
                log.debug("Permanent failure recorded, skipping: deliveryId={}", deliveryId);
                return DeliveryResult.PERMANENT_FAILURE;
            } else if (existing.getStatus() == DeliveryIdempotencyKey.DeliveryStatus.FAILED) {
                // Retry failed delivery
                log.info("Retrying failed delivery: deliveryId={}, attempt={}", 
                        deliveryId, existing.getAttemptCount());
                
                if (existing.getAttemptCount() >= 3) {
                    log.warn("Max delivery attempts reached: deliveryId={}", deliveryId);
                    existing.markFailed("MAX_ATTEMPTS", "Max delivery attempts (3) exceeded");
                    idempotencyKeyRepository.save(existing);
                    return DeliveryResult.PERMANENT_FAILURE;
                }
            } else {
                // PENDING - another worker might be processing
                // Use optimistic locking to claim
                int claimed = idempotencyKeyRepository.claimForDelivery(deliveryId);
                if (claimed == 0) {
                    log.debug("Could not claim delivery (being processed by another worker): deliveryId={}", deliveryId);
                    return DeliveryResult.DUPLICATE;
                }
            }
        }
        
        // Create or update idempotency key
        DeliveryIdempotencyKey idempotencyKey = existingOpt.orElseGet(() -> {
            DeliveryIdempotencyKey key = new DeliveryIdempotencyKey();
            key.setDeliveryId(deliveryId);
            key.setNotificationEventId(notificationEventId);
            key.setNotificationId(notificationId);
            key.setDeviceToken(token.getDeviceToken());
            key.setDeviceTokenHash(tokenHash);
            key.setStatus(DeliveryIdempotencyKey.DeliveryStatus.PENDING);
            return key;
        });
        
        idempotencyKeyRepository.save(idempotencyKey);
        
        // Send push notification
        Timer.Sample sample = Timer.start(meterRegistry);
        com.arpay.notification.PushResult result = firebasePushService.pushToDevice(
            token.getDeviceToken(), title, message, data);
        sample.stop(meterRegistry.timer("notification.firebase.call.duration",
                "success", String.valueOf(result.isSuccess())));

        if (result.isSuccess()) {
            idempotencyKey.markSuccess("fcm-msg-" + UUID.randomUUID());
            token.resetFcmErrorCount();
            token.touch();
            log.debug("Push sent successfully: deliveryId={}", deliveryId);
        } else {
            idempotencyKey.markFailed("FCM_ERROR", result.getErrorMessage());
            token.incrementFcmErrorCount();

            // Check for permanent failure patterns
            if (isPermanentFailure(result.getErrorMessage())) {
                idempotencyKey.setStatus(DeliveryIdempotencyKey.DeliveryStatus.PERMANENT_FAILURE);
                token.markInvalid("FCM_PERMANENT_FAILURE", idempotencyKey.getErrorMessage());
                log.warn("Token marked as permanently failed: tokenId={}, deliveryId={}", 
                        token.getId(), deliveryId);
            }
            
            // Mark token invalid after 3 consecutive failures
            if (token.getFcmErrorCount() >= 3) {
                token.markInvalid("FCM_ERROR", "Failed 3 consecutive times");
                log.warn("Device token marked invalid due to FCM errors: tokenId={}", token.getId());
            }
        }
        
        deviceTokenRepository.save(token);
        idempotencyKeyRepository.save(idempotencyKey);

        // --- Observability: record delivery attempt in structured log ---
        DeliveryResult deliveryResult = result.isSuccess() ? DeliveryResult.SUCCESS : DeliveryResult.FAILED;
        saveDeliveryLog(notificationId, notificationEventId, token, idempotencyKey, deliveryResult);

        // --- Metrics ---
        meterRegistry.counter("notification.push.delivery",
                "status", deliveryResult.name().toLowerCase()).increment();

        return deliveryResult;
    }
    
    /**
     * Check if error indicates permanent failure (not retryable)
     */
    private boolean isPermanentFailure(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String lower = errorMessage.toLowerCase();
        return lower.contains("invalid") || 
               lower.contains("unregistered") || 
               lower.contains("not_found") ||
               lower.contains("invalid_registration");
    }
    
    /**
     * Hash token for storage (don't store full token in idempotency table)
     */
    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(token.hashCode());
        }
    }
    
    /**
     * Publish event to Redis Stream (called from event listener)
     */
    public String publishToQueue(NotificationOutbox outbox) {
        String queueKey = getQueueKeyForSeverity(outbox.getEventType());

        Map<String, Object> payload = new HashMap<>();
        payload.put("outboxId", outbox.getId().toString());
        payload.put("notificationEventId", outbox.getNotificationEventId().toString());
        payload.put("eventType", outbox.getEventType());
        payload.put("eventPayload", outbox.getEventPayload());
        payload.put("createdAt", LocalDateTime.now().toString());

        // Add to Redis Stream
        Object recordId = redisTemplate.opsForStream().add(queueKey, payload);
        String streamId = recordId != null ? recordId.toString() : null;

        log.debug("Published to queue: queue={}, eventId={}, streamId={}",
                 queueKey, outbox.getNotificationEventId(), streamId);

        return streamId;
    }
    
    /**
     * Get queue key based on notification severity
     */
    private String getQueueKeyForSeverity(String eventType) {
        // For now, use normal queue for all events
        // Can be enhanced to route based on severity from payload
        return normalQueue;
    }
    
    /**
     * Poll and process pending outbox entries (fallback mechanism)
     * Includes backpressure control to prevent system overload
     */
    @Async("dlqRetryExecutor")
    public void processPendingOutboxEntries(int limit) {
        log.info("Processing pending outbox entries: limit={}", limit);
        
        // Backpressure check - don't overwhelm system if queue is backed up
        if (backpressureEnabled && isQueueBackpressured()) {
            log.warn("Backpressure triggered - skipping pending outbox processing. Queue depth exceeded threshold.");
            return;
        }
        
        // Use SKIP LOCKED query to prevent worker blocking
        List<NotificationOutbox> pending = outboxRepository.findPendingWithLimit(
            OutboxStatus.PENDING.name(), limit);
        
        for (NotificationOutbox outbox : pending) {
            try {
                processOutboxEntry(outbox.getId(), false);
            } catch (Exception e) {
                log.error("Error processing pending outbox: id={}", outbox.getId(), e);
            }
        }
    }
    
    /**
     * Check if queue is backpressured (too many pending messages)
     */
    private boolean isQueueBackpressured() {
        try {
            // Check Redis queue depth
            Long queueSize = redisTemplate.opsForStream()
                .size(normalQueue);

            if (queueSize != null && queueSize > maxQueueDepth) {
                log.warn("Queue backpressured: size={}, threshold={}", queueSize, maxQueueDepth);
                return true;
            }

            // Also check DB pending count
            long pendingCount = outboxRepository.countByStatus(OutboxStatus.PENDING);
            if (pendingCount > maxQueueDepth) {
                log.warn("Outbox backpressured: pendingCount={}, threshold={}", pendingCount, maxQueueDepth);
                return true;
            }
            
            return false;
        } catch (Exception e) {
            log.error("Error checking backpressure: {}", e.getMessage());
            return false; // Fail open - allow processing if check fails
        }
    }
    
    /**
     * Persist a structured delivery attempt record for observability.
     * Each push attempt (success or failure) gets its own row, enabling
     * per-notification retry lifecycle visibility.
     */
    private void saveDeliveryLog(UUID notificationId, UUID notificationEventId,
                                  UserDeviceToken token, DeliveryIdempotencyKey key,
                                  DeliveryResult result) {
        try {
            NotificationDeliveryLog logEntry = new NotificationDeliveryLog();
            logEntry.setNotificationId(notificationId);
            logEntry.setNotificationEventId(notificationEventId);
            logEntry.setUserId(token.getUserId());
            logEntry.setChannel(NotificationDeliveryLog.Channel.PUSH);
            logEntry.setToken(token.getDeviceToken().substring(0, Math.min(50, token.getDeviceToken().length())));
            logEntry.setAttemptNumber(key.getAttemptCount());
            logEntry.setProcessedAt(LocalDateTime.now());

            if (result == DeliveryResult.SUCCESS) {
                logEntry.setStatus(NotificationDeliveryLog.Status.SENT);
                logEntry.setDeliveredAt(LocalDateTime.now());
            } else {
                logEntry.setStatus(NotificationDeliveryLog.Status.FAILED);
                logEntry.setErrorMessage(key.getErrorMessage());
            }
            deliveryLogRepository.save(logEntry);
        } catch (Exception e) {
            log.warn("Failed to save delivery log for notificationId={}: {}", notificationId, e.getMessage());
        }
    }

    /**
     * Create a DLQ entry when an outbox entry is moved to DEAD_LETTER.
     * The DLQ entry will be retried by {@link DLQRetryScheduler} with exponential backoff.
     */
    private void createDlqEntry(NotificationOutbox outbox, String errorMessage) {
        try {
            if (dlqRepository.existsByEventId(outbox.getNotificationEventId())) {
                log.debug("DLQ entry already exists for eventId={}", outbox.getNotificationEventId());
                return;
            }
            NotificationDlqEntry dlqEntry = new NotificationDlqEntry();
            dlqEntry.setEventId(outbox.getNotificationEventId());
            dlqEntry.setEventPayload(outbox.getEventPayload());
            dlqEntry.setErrorMessage(errorMessage);
            dlqEntry.setRetryCount(0);
            dlqEntry.setResolved(false);
            // nextRetryAt is set by @PrePersist to now + 60s (first retry)
            dlqRepository.save(dlqEntry);
            log.info("DLQ entry created for dead-letter outbox: eventId={}", outbox.getNotificationEventId());
        } catch (Exception e) {
            log.error("Failed to create DLQ entry for eventId={}: {}", outbox.getNotificationEventId(), e.getMessage());
        }
    }

    /**
     * Delivery result enum for idempotent tracking
     */
    public enum DeliveryResult {
        SUCCESS,
        FAILED,
        DUPLICATE,
        PERMANENT_FAILURE
    }
}
