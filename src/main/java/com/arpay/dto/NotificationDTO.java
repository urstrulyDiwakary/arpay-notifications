package com.arpay.dto;

import com.arpay.entity.Notification;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

public class NotificationDTO {
    private UUID id;

    private UUID userId;

    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must not exceed 200 characters")
    private String title;

    @NotBlank(message = "Message is required")
    @Size(max = 2000, message = "Message must not exceed 2000 characters")
    private String message;

    @NotNull(message = "Severity is required")
    private Notification.Severity severity;

    @NotNull(message = "Type is required")
    private Notification.NotificationType type;

    private Boolean isRead;

    @Size(max = 50, message = "Entity type must not exceed 50 characters")
    private String entityType;
    private UUID entityId;
    private UUID notificationEventId;
    @Size(max = 255, message = "Route must not exceed 255 characters")
    private String route;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime scheduledAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime deliverAt;

    private Notification.Status status;

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Notification.Severity getSeverity() { return severity; }
    public void setSeverity(Notification.Severity severity) { this.severity = severity; }
    public Notification.NotificationType getType() { return type; }
    public void setType(Notification.NotificationType type) { this.type = type; }
    public Boolean getIsRead() { return isRead; }
    public void setIsRead(Boolean isRead) { this.isRead = isRead; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public UUID getNotificationEventId() { return notificationEventId; }
    public void setNotificationEventId(UUID notificationEventId) { this.notificationEventId = notificationEventId; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public LocalDateTime getDeliverAt() { return deliverAt; }
    public void setDeliverAt(LocalDateTime deliverAt) { this.deliverAt = deliverAt; }
    public Notification.Status getStatus() { return status; }
    public void setStatus(Notification.Status status) { this.status = status; }
}
