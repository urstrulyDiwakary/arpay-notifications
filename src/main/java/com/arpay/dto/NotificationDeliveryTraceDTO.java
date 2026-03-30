package com.arpay.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Delivery trace for a specific notification.
 * Used for debugging when users report missing notifications.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDeliveryTraceDTO {
    
    private UUID notificationId;
    private UUID userId;
    private String title;
    private String message;
    private String entityType;
    private UUID entityId;
    private LocalDateTime createdAt;
    
    private List<DeliveryAttemptDTO> deliveryAttempts;
    
    private int sentCount;
    private int failedCount;
    private int totalAttempts;
    
    private long avgLatencyMs;
    private long p95LatencyMs;
    
    private String error;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryAttemptDTO {
        private String deviceTokenPrefix;
        private String status;
        private String errorCode;
        private String errorMessage;
        private int attemptCount;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
        private Long latencyMs;
    }
}
