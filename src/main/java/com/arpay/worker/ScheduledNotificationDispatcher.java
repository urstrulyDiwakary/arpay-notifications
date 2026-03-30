package com.arpay.worker;

import com.arpay.entity.Notification;
import com.arpay.entity.UserDeviceToken;
import com.arpay.notification.FirebasePushService;
import com.arpay.repository.NotificationRepository;
import com.arpay.repository.UserDeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Scheduler that processes scheduled notifications that are due for delivery.
 * Runs every 60 seconds to check for notifications whose scheduled time has passed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledNotificationDispatcher {

    private final NotificationRepository notificationRepository;
    private final UserDeviceTokenRepository deviceTokenRepository;
    private final FirebasePushService firebasePushService;

    /**
     * Process scheduled notifications every 60 seconds
     */
    @Scheduled(fixedRate = 60000) // 60 seconds
    @Transactional
    public void processScheduledNotifications() {
        log.debug("Checking for due scheduled notifications...");
        
        LocalDateTime now = LocalDateTime.now();
        List<Notification> dueNotifications = notificationRepository.findDueScheduledNotifications(now);
        
        if (dueNotifications.isEmpty()) {
            log.debug("No scheduled notifications due for delivery");
            return;
        }
        
        log.info("Found {} scheduled notification(s) due for delivery", dueNotifications.size());
        
        int successCount = 0;
        int failureCount = 0;
        
        for (Notification notification : dueNotifications) {
            try {
                log.info("Processing scheduled notification: id={}, userId={}, title={}", 
                    notification.getId(), notification.getUserId(), notification.getTitle());
                
                // Get user's device token
                String deviceToken = getActiveDeviceToken(notification.getUserId());
                
                if (deviceToken == null || deviceToken.isBlank()) {
                    log.warn("No device token found for userId={}, marking notification as SENT but no push sent", 
                        notification.getUserId());
                    // Still mark as SENT since notification exists in DB
                    notification.setStatus(Notification.Status.SENT);
                    notification.setDeliverAt(now);
                    notificationRepository.save(notification);
                    successCount++;
                    continue;
                }
                
                // Send Firebase push notification
                sendFirebasePushNotification(
                    notification.getUserId(),
                    deviceToken,
                    notification.getTitle(),
                    notification.getMessage(),
                    notification.getEntityType(),
                    notification.getEntityId()
                );
                
                // Update notification status and delivery timestamp
                notification.setStatus(Notification.Status.SENT);
                notification.setDeliverAt(now);
                notificationRepository.save(notification);
                
                successCount++;
                log.info("Scheduled notification delivered successfully: id={}", notification.getId());
                
            } catch (Exception e) {
                log.error("Failed to deliver scheduled notification: id={}, error={}", 
                    notification.getId(), e.getMessage(), e);
                
                // Mark as failed
                notification.setStatus(Notification.Status.FAILED);
                notificationRepository.save(notification);
                
                failureCount++;
            }
        }
        
        log.info("Scheduled notification dispatch complete: total={}, success={}, failed={}", 
            dueNotifications.size(), successCount, failureCount);
    }
    
    /**
     * Get the first active device token for a user from UserDeviceToken table
     */
    private String getActiveDeviceToken(UUID userId) {
        List<UserDeviceToken> activeTokens = deviceTokenRepository.findByUserIdAndIsActiveTrue(userId);
        if (activeTokens.isEmpty()) {
            return null;
        }
        // Return the most recently used token (first in the list)
        return activeTokens.get(0).getDeviceToken();
    }
    
    /**
     * Send Firebase push notification to user's device.
     */
    private void sendFirebasePushNotification(UUID userId, String deviceToken, String title, String message, 
                                               String entityType, UUID entityId) {
        try {
            // Build push data
            Map<String, String> data = new HashMap<>();
            data.put("entityType", entityType != null ? entityType : "");
            data.put("entityId", entityId != null ? entityId.toString() : "");
            data.put("notificationType", "INFO");
            data.put("userId", userId.toString());
            data.put("deviceToken", deviceToken); // Pass token for pushToDeviceByUserId

            // Send push notification
            firebasePushService.pushToDeviceByUserId(userId, title, message, data);
            
        } catch (Exception e) {
            log.error("Error sending Firebase push for scheduled notification: {}", e.getMessage(), e);
            throw e; // Re-throw to mark as failed
        }
    }
}
