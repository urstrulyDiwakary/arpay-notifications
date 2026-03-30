package com.arpay.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification_dlq")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class NotificationDlqEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_payload", columnDefinition = "TEXT", nullable = false)
    private String eventPayload;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_retry_at")
    private LocalDateTime lastRetryAt;

    /**
     * When this entry should next be retried (exponential backoff).
     * Null = eligible immediately.
     */
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "resolved", nullable = false)
    private Boolean resolved = false;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        // Seed nextRetryAt for new entries: first retry after 60 seconds
        if (nextRetryAt == null) {
            nextRetryAt = now.plusSeconds(60);
        }
    }
}
