package com.arpay.notification;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import jakarta.annotation.PostConstruct;
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

    /**
     * Log Firebase availability at startup so the root cause of delivery
     * failures is immediately visible — no need to trigger a push to find out.
     * All FCM calls are skipped (and delivery attempts recorded as FAILED)
     * when {@code firebaseMessaging} is null.
     */
    @PostConstruct
    public void logFirebaseStatus() {
        if (firebaseMessaging == null) {
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║  FIREBASE FCM IS DISABLED — push notifications will NOT be  ║");
            log.warn("║  delivered to devices. Delivery is silently skipped; outbox  ║");
            log.warn("║  entries are marked FCM_DISABLED (not FAILED).               ║");
            log.warn("║                                                              ║");
            log.warn("║  To enable FCM push:                                         ║");
            log.warn("║    1. Set FIREBASE_ENABLED=true                              ║");
            log.warn("║    2. Set FIREBASE_SERVICE_ACCOUNT_JSON_BASE64=<base64 JSON> ║");
            log.warn("║       (generate: base64 -w 0 firebase-service-account.json)  ║");
            log.warn("║    3. Ensure the service account is for the correct project  ║");
            log.warn("╚══════════════════════════════════════════════════════════════╝");
        } else {
            log.info("Firebase FCM initialized successfully — push delivery is ACTIVE.");
        }
    }

    public boolean isUnavailable() {
        return firebaseMessaging == null;
    }

    /**
     * Send push to a single device token
     * @return PushResult indicating success, token invalid, or transient failure
     */
    public PushResult pushToDevice(String token, String title, String message, Map<String, String> data) {
        if (isUnavailable() || token == null || token.isBlank()) {
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
     * Send push to multiple device tokens (batch).
     * Retained for bulk-send scenarios; callers may discard the BatchResponse if not needed.
     */
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public BatchResponse pushToDevices(List<String> tokens, String title, String message, Map<String, String> data) {
        if (isUnavailable() || tokens == null || tokens.isEmpty()) {
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
     * Send push to all devices subscribed to a topic.
     * Returns true on success; callers may ignore the return value if fire-and-forget is sufficient.
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean pushToTopic(String topic, String title, String message, Map<String, String> data) {
        if (isUnavailable() || topic == null || topic.isBlank()) {
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
        // Use a mutable copy so we don't mutate the caller's map
        Map<String, String> fcmData = data != null ? new HashMap<>(data) : new HashMap<>();

        // Always include title and body in data so the service worker onBackgroundMessage
        // handler can build the notification without a separate 'notification' payload.
        fcmData.putIfAbsent("title", title != null ? title : "ARPAY Notification");
        fcmData.putIfAbsent("body", message != null ? message : "");

        // Preserve the caller-supplied notificationEventId / notificationId.
        // Only generate a fallback id when neither key is present.
        if (!fcmData.containsKey("notificationEventId") && !fcmData.containsKey("notificationId")) {
            fcmData.put("notificationId", UUID.randomUUID().toString());
        }

        // ---------------------------------------------------------------
        // DATA-ONLY message — no 'notification' payload.
        //
        // Rationale: when a message carries both a 'notification' payload
        // AND a 'data' payload, Chrome/Firebase automatically displays the
        // notification from the 'notification' field AND calls
        // onBackgroundMessage() in the service worker, which would call
        // showNotification() again — resulting in duplicate notifications.
        //
        // By sending a data-only message the service worker's
        // onBackgroundMessage handler is the single place that creates the
        // visible notification, giving it full control over title, body,
        // icon, tag, route and requireInteraction.
        // ---------------------------------------------------------------
        return Message.builder()
                .setToken(token)
                // HIGH priority wakes up Android / Doze-mode devices and iOS in background.
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build())
                // content-available=1 lets iOS wake the app in the background so the
                // service worker can handle the data-only push.
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder()
                                .setContentAvailable(true)
                                .setSound("default")
                                .build())
                        .build())
                .putAllData(fcmData)
                .build();
    }

    private Message buildTopicMessage(String topic, String title, String message, Map<String, String> data) {
        Map<String, String> fcmData = data != null ? new HashMap<>(data) : new HashMap<>();
        fcmData.putIfAbsent("title", title != null ? title : "ARPAY Notification");
        fcmData.putIfAbsent("body", message != null ? message : "");
        if (!fcmData.containsKey("notificationEventId") && !fcmData.containsKey("notificationId")) {
            fcmData.put("notificationId", UUID.randomUUID().toString());
        }

        return Message.builder()
                .setTopic(topic)
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder()
                                .setContentAvailable(true)
                                .setSound("default")
                                .build())
                        .build())
                .putAllData(fcmData)
                .build();
    }
}
