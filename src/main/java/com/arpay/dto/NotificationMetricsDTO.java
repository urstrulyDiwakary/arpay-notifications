package com.arpay.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMetricsDTO {
    private Long totalNotifications;
    private Long unreadCount;
    private Long deliveredCount;
    private Long failedCount;
    private Double avgDeliveryTimeMs;
    private Map<String, Integer> workerStats;
    private Long dlqSize;
}
