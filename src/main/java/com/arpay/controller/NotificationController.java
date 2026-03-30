package com.arpay.controller;

import com.arpay.dto.NotificationDTO;
import com.arpay.dto.NotificationMetricsDTO;
import com.arpay.dto.PageResponse;
import com.arpay.entity.Notification;
import com.arpay.entity.User;
import com.arpay.repository.NotificationRepository;
import com.arpay.repository.UserRepository;
import com.arpay.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    /**
     * Create a new notification
     */
    @PostMapping
    public ResponseEntity<NotificationDTO> createNotification(@RequestBody NotificationDTO notificationDTO) {
        log.info("Creating notification for userId={}, title={}", notificationDTO.getUserId(), notificationDTO.getTitle());
        NotificationDTO created = notificationService.createNotification(notificationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Send notification to a specific user
     */
    @PostMapping("/send/user")
    public ResponseEntity<Map<String, Object>> sendToUser(@RequestBody Map<String, Object> request) {
        try {
            UUID userId = UUID.fromString(request.get("userId").toString());
            String title = (String) request.get("title");
            String message = (String) request.get("message");
            String entityType = (String) request.get("entityType");
            UUID entityId = request.get("entityId") != null ? UUID.fromString(request.get("entityId").toString()) : null;

            notificationService.notifyUser(userId, title, message, entityType, entityId);

            Map<String, Object> response = new HashMap<>();
            response.put("sent", true);
            response.put("userId", userId.toString());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to send notification to user: {}", e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("sent", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Send notification to a role
     */
    @PostMapping("/send/role")
    public ResponseEntity<Map<String, Object>> sendToRole(@RequestBody Map<String, Object> request) {
        try {
            String role = (String) request.get("role");
            String title = (String) request.get("title");
            String message = (String) request.get("message");
            String entityType = (String) request.get("entityType");
            UUID entityId = request.get("entityId") != null ? UUID.fromString(request.get("entityId").toString()) : null;
            String route = (String) request.get("route");

            notificationService.notifyRole(role, title, message, entityType, entityId, route);

            Map<String, Object> response = new HashMap<>();
            response.put("sent", true);
            response.put("role", role);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to send notification to role: {}", e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("sent", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get notifications for a user with pagination and filters
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getNotifications(
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String read,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String type
    ) {
        log.info("Fetching notifications for userId={}, page={}, size={}", userId, page, size);

        PageResponse<NotificationDTO> response;
        if (type != null) {
            response = notificationService.getNotificationsByUserId(userId, page, size, sortBy, sortDir, search, read, priority, type);
        } else if (read != null || priority != null || search != null) {
            response = notificationService.getNotificationsByUserId(userId, page, size, sortBy, sortDir, search, read, priority);
        } else {
            response = notificationService.getNotificationsByUserId(userId, page, size);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("content", response.getContent());
        result.put("page", response.getPage());
        result.put("size", response.getSize());
        result.put("totalElements", response.getTotalElements());
        result.put("totalPages", response.getTotalPages());
        result.put("first", response.isFirst());
        result.put("last", response.isLast());

        return ResponseEntity.ok(result);
    }

    /**
     * Get unread notification count for a user
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(@RequestParam UUID userId) {
        log.info("Fetching unread count for userId={}", userId);
        long count = notificationService.countUnreadByUserId(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("count", count);
        response.put("userId", userId.toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Get recent notifications for a user
     */
    @GetMapping("/recent")
    public ResponseEntity<List<NotificationDTO>> getRecentNotifications(
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        log.info("Fetching recent notifications for userId={}, limit={}", userId, limit);
        List<NotificationDTO> notifications = notificationService.getRecentNotificationsByUserId(userId, limit);
        return ResponseEntity.ok(notifications);
    }

    /**
     * Mark a notification as read
     */
    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationDTO> markAsRead(@PathVariable UUID id) {
        log.info("Marking notification as read: id={}", id);
        NotificationDTO notification = notificationService.markAsRead(id);
        return ResponseEntity.ok(notification);
    }

    /**
     * Mark all notifications as read for a user
     */
    @PatchMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllAsRead(@RequestParam UUID userId) {
        log.info("Marking all notifications as read for userId={}", userId);
        int count = notificationService.markAllAsReadByUserId(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("markedCount", count);
        response.put("userId", userId.toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a notification
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotification(@PathVariable UUID id) {
        log.info("Deleting notification: id={}", id);
        notificationService.deleteNotification(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Clear all notifications for a user
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearAll(@RequestParam UUID userId) {
        log.info("Clearing all notifications for userId={}", userId);
        long count = notificationService.clearAllByUserId(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("deletedCount", count);
        response.put("userId", userId.toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Get notification metrics (for admin dashboard)
     */
    @GetMapping("/metrics")
    public ResponseEntity<NotificationMetricsDTO> getMetrics() {
        log.info("Fetching notification metrics");
        // TODO: Implement metrics endpoint
        NotificationMetricsDTO metrics = new NotificationMetricsDTO();
        return ResponseEntity.ok(metrics);
    }
}
