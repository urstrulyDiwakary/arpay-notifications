package com.arpay.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private Severity severity = Severity.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    @Column(length = 50)
    private String entityType;

    @Column
    private UUID entityId;

    @Column(name = "notification_event_id", nullable = false)
    private UUID notificationEventId;

    @Column(length = 255)
    private String route;

    @Column(nullable = false)
    private Boolean isRead = false;

    @Column
    private LocalDateTime readAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "deliver_at")
    private LocalDateTime deliverAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.SENT;

    @PrePersist
    @PreUpdate
    private void applyDefaults() {
        if (severity == null) {
            severity = Severity.NORMAL;
        }
        if (status == null) {
            status = Status.SENT;
        }
    }

    public enum Severity {
        LOW, NORMAL, MEDIUM, HIGH, CRITICAL
    }

    public enum NotificationType {
        INFO, WARNING, SUCCESS, ERROR
    }

    public enum Status {
        PENDING, SCHEDULED, SENT, FAILED, CANCELLED
    }
}
