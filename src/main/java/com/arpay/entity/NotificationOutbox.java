package com.arpay.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Transactional outbox entity for reliable event publishing.
 * Ensures atomicity between database writes and queue publishing.
 */
@Entity
@Table(name = "notification_outbox",
       indexes = {
           @Index(name = "idx_outbox_status", columnList = "status, created_at"),
           @Index(name = "idx_outbox_event_type", columnList = "event_type"),
           @Index(name = "idx_outbox_notification_id", columnList = "notification_id")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;
    
    @Column(name = "notification_event_id", nullable = false)
    private UUID notificationEventId;
    
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;
    
    @Column(name = "event_payload", columnDefinition = "TEXT", nullable = false)
    private String eventPayload;
    
    @Column(name = "queue_name", length = 100)
    private String queueName;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;
    
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    @Column(name = "stream_id", length = 100)
    private String streamId;
    
    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (retryCount == null) {
            retryCount = 0;
        }
        if (status == null) {
            status = OutboxStatus.PENDING;
        }
    }
    
    /**
     * Mark as queued (dispatched to async worker, awaiting processing).
     * The entry will be changed to PUBLISHED after actual FCM delivery.
     */
    public void markQueued() {
        this.status = OutboxStatus.QUEUED;
        this.lastAttemptAt = LocalDateTime.now();
    }

    /**
     * Mark as published successfully
     */
    public void markPublished(String streamId) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.streamId = streamId;
    }
    
    /**
     * Mark as failed, will be retried
     */
    public void markFailed(String error) {
        this.status = OutboxStatus.FAILED;
        this.errorMessage = error;
        this.lastAttemptAt = LocalDateTime.now();
        this.retryCount = this.retryCount + 1;
    }
    
    /**
     * Mark as dead letter (max retries exceeded)
     */
    public void markDeadLetter(String error) {
        this.status = OutboxStatus.DEAD_LETTER;
        this.errorMessage = error;
        this.lastAttemptAt = LocalDateTime.now();
    }
    
    /**
     * Check if this event can be retried
     */
    public boolean canRetry(int maxRetries) {
        return this.retryCount < maxRetries;
    }
}
