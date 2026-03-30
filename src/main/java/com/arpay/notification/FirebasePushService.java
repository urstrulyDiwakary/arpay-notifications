package com.arpay.notification;

import com.google.firebase.messaging.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class FirebasePushService {

    @Autowired(required = false)
    private FirebaseMessaging firebaseMessaging;

    public FirebasePushService() {
    }

    public boolean isAvailable() {
        return firebaseMessaging != null;
    }

    /**
     * Send push to a single device token
     * @return PushResult indicating success, token invalid, or transient failure
     */
    public PushResult pushToDevice(String token, String title, String message, Map<String, String> data) {
        if (!isAvailable() || token == null || token.isBlank()) {
            return PushResult.failed("FCM not available or token missing");
        }

        try {
            Message push = buildMessage(token, title, message, data);
            String response = firebaseMessaging.send(push);
            log.info("FCM push sent successfully to device token={} response={}",
                    token.substring(0, Math.min(20, token.length())), response);
            return PushResult.success();
        } catch (FirebaseMessagingException e) {
            String errorCode = e.getErrorCode() != null ? e.getErrorCode().name() : "UNKNOWN";
            log.error("FCM push failed token={} error={} code={}", token, e.getMessage(), errorCode);

            // Check if token is permanently invalid (device unregistered, invalid token, etc.)
            boolean tokenInvalid = "UNREGISTERED".equals(errorCode)
                || "INVALID_ARGUMENT".equals(errorCode)
                || "NOT_FOUND".equals(errorCode);

            if (tokenInvalid) {
                log.warn("FCM token marked as invalid ({}), should be removed: token={}", errorCode,
                    token.substring(0, Math.min(20, token.length())));
                return PushResult.tokenInvalid(e.getMessage());
            }

            // Transient error (e.g., UNAVAILABLE, INTERNAL) - may retry later
            return PushResult.failed(e.getMessage());
        }
    }

    /**
     * Send push notification to a user by their UUID.
     * The actual device token lookup should be handled by the caller.
     * This method expects the token to be passed in the data map under "deviceToken" key.
     * 
     * @param userId User UUID
     * @param title Notification title
     * @param message Notification message
     * @param data Additional data payload (should include "deviceToken" key)
     * @throws IllegalArgumentException if device token is not provided in data
     */
    public void pushToDeviceByUserId(UUID userId, String title, String message, Map<String, String> data) {
        String deviceToken = data != null ? data.remove("deviceToken") : null;
        if (deviceToken == null || deviceToken.isBlank()) {
            log.warn("No device token provided for userId={}, skipping push", userId);
            return;
        }
        
        PushResult result = pushToDevice(deviceToken, title, message, data);
        if (!result.isSuccess()) {
            throw new RuntimeException("Failed to send push notification: " + result.getErrorMessage());
        }
    }

    /**
     * Send push to multiple device tokens (batch)
     */
    public BatchResponse pushToDevices(List<String> tokens, String title, String message, Map<String, String> data) {
        if (!isAvailable() || tokens == null || tokens.isEmpty()) {
            return null;
        }

        try {
            List<Message> messages = tokens.stream()
                    .map(token -> buildMessage(token, title, message, data))
                    .toList();
            
            BatchResponse response = firebaseMessaging.sendEach(messages);
            log.info("FCM batch push sent: success={} failure={}", 
                    response.getSuccessCount(), response.getFailureCount());
            return response;
        } catch (FirebaseMessagingException e) {
            log.error("FCM batch push failed error={}", e.getMessage());
            return null;
        }
    }

    /**
     * Send push to all devices subscribed to a topic
     */
    public boolean pushToTopic(String topic, String title, String message, Map<String, String> data) {
        if (!isAvailable() || topic == null || topic.isBlank()) {
            return false;
        }

        try {
            Message push = buildTopicMessage(topic, title, message, data);
            String response = firebaseMessaging.send(push);
            log.info("FCM topic push sent successfully topic={} response={}", topic, response);
            return true;
        } catch (FirebaseMessagingException e) {
            log.error("FCM topic push failed topic={} error={}", topic, e.getMessage());
            return false;
        }
    }

    private Message buildMessage(String token, String title, String message, Map<String, String> data) {
        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(message)
                .build();

        // Generate a unique notification ID for proper Android notification handling
        String notificationId = UUID.randomUUID().toString();

        Message.Builder builder = Message.builder()
                .setToken(token)
                .setNotification(notification)
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setClickAction("OPEN_NOTIFICATION")
                                .setChannelId("arpay_notifications") // Must match app's notification channel
                                .build())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder()
                                .setCategory("NOTIFICATION")
                                .setSound("default")
                                .build())
                        .build());

        // Add notificationId to data payload for app handling
        if (data != null) {
            data.put("notificationId", notificationId);
            builder.putAllData(data);
        } else {
            builder.putData("notificationId", notificationId);
        }

        return builder.build();
    }

    private Message buildTopicMessage(String topic, String title, String message, Map<String, String> data) {
        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(message)
                .build();

        Message.Builder builder = Message.builder()
                .setTopic(topic)
                .setNotification(notification)
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder()
                                .setCategory("NOTIFICATION")
                                .setSound("default")
                                .build())
                        .build());

        if (data != null && !data.isEmpty()) {
            builder.putAllData(data);
        }

        return builder.build();
    }
}
