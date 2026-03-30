package com.arpay.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin dashboard DTO - shows notification delivery health at a glance
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationHealthDTO {
    
    /**
     * Summary statistics
     */
    private Summary summary;
    
    /**
     * Recent failed deliveries (last 100)
     */
    private List<FailedDeliveryDTO> recentFailures;
    
    /**
     * DLQ entries pending review
     */
    private List<DLQEntryDTO> dlqEntries;
    
    /**
     * Recently invalidated tokens
     */
    private List<InvalidatedTokenDTO> invalidatedTokens;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private long totalNotificationsLast24h;
        private long successfulDeliveries;
        private long failedDeliveries;
        private long duplicateDeliveries;
        private double successRatePercent;
        private int dlqSize;
        private int pendingOutboxSize;
        private int invalidatedTokenCount;
        private LocalDateTime lastRefreshAt;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedDeliveryDTO {
        private UUID notificationId;
        private UUID userId;
        private String title;
        private String deviceTokenPrefix;
        private String errorCode;
        private String errorMessage;
        private int attemptCount;
        private LocalDateTime createdAt;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DLQEntryDTO {
        private UUID id;
        private UUID eventId;
        private String errorMessage;
        private int retryCount;
        private LocalDateTime createdAt;
        private LocalDateTime lastRetryAt;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvalidatedTokenDTO {
        private UUID tokenId;
        private UUID userId;
        private String deviceType;
        private String invalidationReason;
        private String errorMessagePrefix;
        private LocalDateTime invalidatedAt;
    }
}
