package com.arpay.controller;

import com.arpay.dto.DeviceTokenDTO;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications/tokens")
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenController {

    private final UserRepository userRepository;
    private final UserDeviceTokenRepository deviceTokenRepository;

    /**
     * Register or update FCM device token for a user.
     * Supports multiple devices per user.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> registerDeviceToken(@RequestBody Map<String, String> request) {
        try {
            String userIdStr = request.get("userId");
            String token = request.get("token");
            String deviceType = request.get("deviceType");
            String appVersion = request.get("appVersion");
            String platformVersion = request.get("platformVersion");
            String appIdentifier = request.get("appIdentifier");

            if (userIdStr == null || token == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "userId and token are required");
                return ResponseEntity.badRequest().body(error);
            }

            UUID userId = UUID.fromString(userIdStr);
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "User not found: " + userId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            // Check if token already exists — use List to safely handle legacy duplicates
            List<UserDeviceToken> existingTokens = deviceTokenRepository.findByDeviceToken(token);

            UserDeviceToken deviceToken;
            if (!existingTokens.isEmpty()) {
                // Sort by createdAt desc — keep the newest record
                existingTokens.sort(Comparator.comparing(
                        t -> t.getCreatedAt() != null ? t.getCreatedAt() : LocalDateTime.MIN,
                        Comparator.reverseOrder()));

                deviceToken = existingTokens.getFirst();

                // Delete any duplicates (all but the first/newest)
                if (existingTokens.size() > 1) {
                    List<UserDeviceToken> duplicates = existingTokens.subList(1, existingTokens.size());
                    deviceTokenRepository.deleteAll(duplicates);
                    log.warn("Removed {} duplicate device token entries for token prefix={}",
                            duplicates.size(),
                            token.length() > 20 ? token.substring(0, 20) : token);
                }

                // Update existing token
                deviceToken.setLastUsedAt(LocalDateTime.now());
                deviceToken.setIsActive(true);
                if (deviceType != null) {
                    deviceToken.setDeviceType(UserDeviceToken.DeviceType.valueOf(deviceType.toUpperCase()));
                }
                if (appVersion != null) {
                    deviceToken.setAppVersion(appVersion);
                }
                log.info("Updated existing device token: tokenId={}", deviceToken.getId());
            } else {
                // Create new token
                deviceToken = new UserDeviceToken();
                deviceToken.setUserId(userId);
                deviceToken.setDeviceToken(token);
                if (deviceType != null) {
                    deviceToken.setDeviceType(UserDeviceToken.DeviceType.valueOf(deviceType.toUpperCase()));
                }
                if (appVersion != null) {
                    deviceToken.setAppVersion(appVersion);
                }
                if (platformVersion != null) {
                    deviceToken.setPlatformVersion(platformVersion);
                }
                if (appIdentifier != null) {
                    deviceToken.setAppIdentifier(appIdentifier);
                }
                log.info("Created new device token: tokenId={}", deviceToken.getId());
            }

            deviceTokenRepository.save(deviceToken);

            // Also update legacy field on User entity for backward compatibility
            User user = userOpt.get();
            if (user.getDeviceToken() == null || user.getDeviceToken().isBlank()) {
                user.setDeviceToken(token);
                userRepository.save(user);
            }

            log.info("Registered device token for userId={}, tokenPrefix={}", 
                    userId, token.length() > 20 ? token.substring(0, 20) : token);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("userId", userId.toString());
            response.put("tokenId", deviceToken.getId().toString());
            response.put("message", "Device token registered successfully");
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("Invalid UUID format: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Invalid UUID format: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Failed to register device token: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get all device tokens for a user
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getDeviceTokens(@RequestParam UUID userId) {
        try {
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "User not found: " + userId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            List<UserDeviceToken> tokens = deviceTokenRepository.findByUserIdAndIsActiveTrue(userId);
            List<DeviceTokenDTO> tokenDTOs = tokens.stream()
                .map(DeviceTokenDTO::fromEntity)
                .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("userId", userId.toString());
            response.put("tokens", tokenDTOs);
            response.put("count", tokens.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get device tokens: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get a specific device token by ID
     */
    @GetMapping("/{tokenId}")
    public ResponseEntity<Map<String, Object>> getDeviceToken(@PathVariable UUID tokenId) {
        try {
            Optional<UserDeviceToken> tokenOpt = deviceTokenRepository.findById(tokenId);

            if (tokenOpt.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "Token not found: " + tokenId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("token", DeviceTokenDTO.fromEntity(tokenOpt.get()));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get device token: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Delete a specific device token
     */
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Map<String, Object>> deleteDeviceToken(@PathVariable UUID tokenId) {
        try {
            Optional<UserDeviceToken> tokenOpt = deviceTokenRepository.findById(tokenId);

            if (tokenOpt.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "Token not found: " + tokenId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            deviceTokenRepository.deleteById(tokenId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("tokenId", tokenId.toString());
            response.put("message", "Device token removed successfully");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to delete device token: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Delete all device tokens for a user (logout from all devices)
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteAllDeviceTokens(@RequestParam UUID userId) {
        try {
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", "User not found: " + userId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            int deletedCount = deviceTokenRepository.deactivateAllTokensForUser(userId, "USER_LOGOUT");

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("userId", userId.toString());
            response.put("deletedCount", deletedCount);
            response.put("message", "All device tokens removed successfully");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to delete all device tokens: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
