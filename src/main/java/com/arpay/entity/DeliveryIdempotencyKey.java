package com.arpay.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Idempotency key for delivery operations.
 * Prevents duplicate deliveries when workers crash and retry.
 * 
 * This is the REAL exactly-once mechanism - at the delivery level,
 * not just the notification creation level.
 */
@Entity
@Table(name = "delivery_idempotency_keys",
       indexes = {
           @Index(name = "idx_idempotency_delivery_id", columnList = "delivery_id"),
           @Index(name = "idx_idempotency_event_id", columnList = "notification_event_id"),
           @Index(name = "idx_idempotency_token", columnList = "device_token, created_at")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryIdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    /**
     * Unique delivery attempt ID - generated per (notification, token) pair
     * Format: {notificationEventId}:{deviceTokenHash}
     */
    @Column(name = "delivery_id", nullable = false, unique = true, length = 100)
    private String deliveryId;
    
    @Column(name = "notification_event_id", nullable = false)
    private UUID notificationEventId;
    
    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;
    
    @Column(name = "device_token", nullable = false, length = 500)
    private String deviceToken;
    
    @Column(name = "device_token_hash", length = 64)
    private String deviceTokenHash;
    
    /**
     * Status of this delivery attempt
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DeliveryStatus status = DeliveryStatus.PENDING;
    
    @Column(name = "fcm_message_id", length = 200)
    private String fcmMessageId;
    
    @Column(name = "fcm_error_code", length = 50)
    private String fcmErrorCode;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 1;
    
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;
    
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    
    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (attemptCount == null) {
            attemptCount = 1;
        }
        if (status == null) {
            status = DeliveryStatus.PENDING;
        }
        // Default expiry: 24 hours
        if (expiresAt == null) {
            expiresAt = LocalDateTime.now().plusHours(24);
        }
    }
    
    /**
     * Mark delivery as successfully sent
     */
    public void markSuccess(String fcmMessageId) {
        this.status = DeliveryStatus.SENT;
        this.fcmMessageId = fcmMessageId;
        this.completedAt = LocalDateTime.now();
    }
    
    /**
     * Mark delivery as failed
     */
    public void markFailed(String errorCode, String errorMessage) {
        this.status = DeliveryStatus.FAILED;
        this.fcmErrorCode = errorCode;
        this.errorMessage = errorMessage;
        this.attemptCount = this.attemptCount + 1;
        this.lastAttemptAt = LocalDateTime.now();
    }
    
    /**
     * Check if this delivery is expired (for cleanup)
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
    
    /**
     * Generate unique delivery ID for idempotency
     */
    public static String generateDeliveryId(UUID notificationEventId, String deviceToken) {
        // Hash the token to keep delivery ID reasonable length
        String tokenHash = Integer.toHexString(deviceToken.hashCode());
        return notificationEventId.toString() + ":" + tokenHash;
    }
    
    public enum DeliveryStatus {
        PENDING,      // Delivery attempt not yet completed
        SENT,         // Successfully sent to FCM
        FAILED,       // Delivery failed (can retry)
        PERMANENT_FAILURE, // Permanent failure (invalid token, etc.)
        DUPLICATE     // Detected as duplicate delivery attempt
    }
}
