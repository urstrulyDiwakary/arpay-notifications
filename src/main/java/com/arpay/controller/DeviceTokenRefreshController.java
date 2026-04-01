package com.arpay.controller;

import com.arpay.entity.User;
import com.arpay.entity.UserDeviceToken;
import com.arpay.repository.UserRepository;
import com.arpay.repository.UserDeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Device token management with refresh support.
 *
 * CRITICAL: This endpoint solves token drift - the #1 cause of silent delivery failures.
 *
 * Frontend should call this:
 * - On app login
 * - On app resume (if token changed)
 * - When FCM token refresh event fires
 */
@RestController
@RequestMapping("/api/notifications/tokens")
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenRefreshController {
    
    private final UserRepository userRepository;
    private final UserDeviceTokenRepository deviceTokenRepository;
    
    /**
     * Register or refresh device token.
     *
     * Call this from frontend whenever FCM token changes:
     * - App login
     * - Token refresh event
     * - App resume (periodic check)
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshDeviceToken(@RequestBody Map<String, String> request) {
        try {
            String userIdStr = request.get("userId");
            String token = request.get("token");
            String deviceType = request.get("deviceType");
            String appVersion = request.get("appVersion");
            
            if (userIdStr == null || token == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "userId and token are required");
                return ResponseEntity.badRequest().body(error);
            }
            
            UUID userId = UUID.fromString(userIdStr);
            
            // Verify user exists
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "User not found: " + userId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }
            
            // Check if this token already exists (active or inactive)
            // Use findFirstByDeviceTokenOrderByCreatedAtDesc — safe even when legacy duplicates exist
            Optional<UserDeviceToken> existingToken =
                    deviceTokenRepository.findFirstByDeviceTokenOrderByCreatedAtDesc(token);
            
            UserDeviceToken deviceToken;
            if (existingToken.isPresent()) {
                deviceToken = existingToken.get();
                
                // If token was invalidated, reactivate it (token was reused)
                if (!deviceToken.getIsActive()) {
                    log.info("Reactivating previously invalidated token: tokenId={}, userId={}", 
                            deviceToken.getId(), userId);
                    deviceToken.setIsActive(true);
                    deviceToken.setInvalidatedAt(null);
                    deviceToken.setInvalidationReason(null);
                }
                
                // Update metadata
                deviceToken.setLastUsedAt(LocalDateTime.now());
                if (deviceType != null) {
                    deviceToken.setDeviceType(UserDeviceToken.DeviceType.valueOf(deviceType.toUpperCase()));
                }
                if (appVersion != null) {
                    deviceToken.setAppVersion(appVersion);
                }
                
                log.info("Refreshed existing device token: tokenId={}, userId={}", deviceToken.getId(), userId);
            } else {
                // New token - create record
                deviceToken = new UserDeviceToken();
                deviceToken.setUserId(userId);
                deviceToken.setDeviceToken(token);
                if (deviceType != null) {
                    deviceToken.setDeviceType(UserDeviceToken.DeviceType.valueOf(deviceType.toUpperCase()));
                }
                if (appVersion != null) {
                    deviceToken.setAppVersion(appVersion);
                }
                
                log.info("Registered new device token: tokenId={}, userId={}", deviceToken.getId(), userId);
            }
            
            deviceTokenRepository.save(deviceToken);
            
            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("tokenId", deviceToken.getId().toString());
            response.put("isNew", existingToken.isEmpty());
            response.put("wasReactivated", existingToken.isPresent() && !existingToken.get().getIsActive());
            response.put("message", "Token refreshed successfully");
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid UUID format: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Invalid UUID format: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Failed to refresh device token: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
    
}
