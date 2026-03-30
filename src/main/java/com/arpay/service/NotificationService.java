package com.arpay.service;

import com.arpay.dto.NotificationDTO;
import com.arpay.dto.PageResponse;

import java.util.List;
import java.util.UUID;

public interface NotificationService {
    NotificationDTO createNotification(NotificationDTO notificationDTO);
    NotificationDTO getNotificationById(UUID id);
    PageResponse<NotificationDTO> getNotificationsByUserId(UUID userId, int page, int size);
    PageResponse<NotificationDTO> getNotificationsByUserId(UUID userId, int page, int size,
                                                           String sortBy, String sortDir,
                                                           String search, String read, String priority);
    PageResponse<NotificationDTO> getNotificationsByUserId(UUID userId, int page, int size,
                                                           String sortBy, String sortDir,
                                                           String search, String read, String priority, String type);
    List<NotificationDTO> getRecentNotificationsByUserId(UUID userId, int limit);
    NotificationDTO markAsRead(UUID id);
    int markAllAsReadByUserId(UUID userId);
    long clearAllByUserId(UUID userId);
    void deleteNotification(UUID id);
    long countUnreadByUserId(UUID userId);

    void notifyUser(UUID userId, String title, String message, String entityType, UUID entityId);
    void notifyUsers(List<UUID> userIds, String title, String message, String entityType, UUID entityId);
    void notifyRole(String role, String title, String message, String entityType, UUID entityId, String route);
    void notifyTopic(String topic, String title, String message, String entityType, UUID entityId, String route);
    void createNotificationIfNotExists(UUID userId, String title, String message, String entityType, UUID entityId);

    int retryDlqEvents(int limit);
}
