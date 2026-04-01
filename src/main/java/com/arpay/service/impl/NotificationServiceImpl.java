package com.arpay.service.impl;

import com.arpay.dto.NotificationDTO;
import com.arpay.dto.PageResponse;
import com.arpay.entity.Notification;
import com.arpay.entity.NotificationOutbox;
import com.arpay.entity.OutboxStatus;
import com.arpay.entity.User;
import com.arpay.event.NotificationCreatedEvent;
import com.arpay.notification.DeduplicationService;
import com.arpay.notification.FirebasePushService;
import com.arpay.notification.UnreadCountCacheService;
import com.arpay.repository.NotificationOutboxRepository;
import com.arpay.repository.NotificationRepository;
import com.arpay.repository.UserRepository;
import com.arpay.service.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final FirebasePushService firebasePushService;
    private final DeduplicationService deduplicationService;
    private final UnreadCountCacheService unreadCountCacheService;
    // --- Transactional outbox pattern ---
    private final NotificationOutboxRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public NotificationDTO createNotification(NotificationDTO notificationDTO) {
        log.info("Creating notification for userId={}, title={}", notificationDTO.getUserId(), notificationDTO.getTitle());

        Notification notification = toEntity(notificationDTO);
        if (notification.getNotificationEventId() == null) {
            notification.setNotificationEventId(UUID.randomUUID());
        }
        if (notification.getSeverity() == null) {
            notification.setSeverity(Notification.Severity.NORMAL);
        }
        if (notification.getType() == null) {
            notification.setType(Notification.NotificationType.INFO);
        }
        if (notification.getStatus() == null) {
            notification.setStatus(Notification.Status.SENT);
        }

        // Handle scheduled notifications
        LocalDateTime now = LocalDateTime.now();
        if (notification.getScheduledAt() != null && notification.getScheduledAt().isAfter(now)) {
            notification.setStatus(Notification.Status.SCHEDULED);
            log.info("Notification scheduled for future delivery: userId={}, scheduledAt={}",
                notification.getUserId(), notification.getScheduledAt());
        } else {
            if (notification.getScheduledAt() == null) {
                notification.setScheduledAt(now);
            }
            notification.setStatus(Notification.Status.SENT);
        }

        Notification saved = notificationRepository.save(notification);

        if (notification.getStatus() == Notification.Status.SENT) {
            // Increment unread count immediately (in-memory, fast)
            unreadCountCacheService.increment(saved.getUserId());

            // Record intended delivery time
            saved.setDeliverAt(now);
            notificationRepository.save(saved);

            // --- Transactional Outbox Pattern ---
            // Atomically write the outbox entry in the same transaction.
            // After this transaction commits, the @TransactionalEventListener will
            // pick up the event and dispatch it to the async worker for FCM delivery.
            NotificationOutbox outbox = buildOutboxEntry(saved);
            outboxRepository.save(outbox);

            // Publish the domain event. Spring buffers it and fires it AFTER commit
            // via @TransactionalEventListener(phase = AFTER_COMMIT) in NotificationEventListener.
            eventPublisher.publishEvent(new NotificationCreatedEvent(this, saved));
        } else {
            log.info("Notification scheduled, push will be sent at scheduled time: id={}", saved.getId());
        }

        log.info("Notification created: id={}, status={}", saved.getId(), saved.getStatus());
        return toDto(saved);
    }

    /**
     * Build a NotificationOutbox entry for the given notification.
     * The payload is the JSON data the worker needs to perform FCM delivery.
     */
    private NotificationOutbox buildOutboxEntry(Notification notification) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("notificationId", notification.getId().toString());
        payload.put("userId", notification.getUserId().toString());
        payload.put("title", notification.getTitle());
        payload.put("message", notification.getMessage());
        payload.put("entityType", notification.getEntityType());
        payload.put("entityId", notification.getEntityId() != null ? notification.getEntityId().toString() : null);
        payload.put("severity", notification.getSeverity() != null ? notification.getSeverity().name() : "NORMAL");
        payload.put("type", notification.getType() != null ? notification.getType().name() : "INFO");
        payload.put("route", notification.getRoute());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload for notificationId=" + notification.getId(), e);
        }

        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setNotificationId(notification.getId());
        outbox.setNotificationEventId(notification.getNotificationEventId());
        outbox.setEventType("NOTIFICATION_CREATED");
        outbox.setEventPayload(payloadJson);
        outbox.setStatus(OutboxStatus.PENDING);
        return outbox;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationDTO getNotificationById(UUID id) {
        log.info("Fetching notification by id={}", id);
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Notification not found with id: " + id));
        return toDto(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationDTO> getNotificationsByUserId(UUID userId, int page, int size) {
        return getNotificationsByUserId(userId, page, size, "createdAt", "desc", null, null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationDTO> getNotificationsByUserId(UUID userId, int page, int size,
                                                                   String sortBy, String sortDir,
                                                                   String search, String read, String priority) {
        return getNotificationsByUserId(userId, page, size, sortBy, sortDir, search, read, priority, null);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationDTO> getNotificationsByUserId(UUID userId, int page, int size,
                                                                   String sortBy, String sortDir,
                                                                   String search, String read, String priority, String type) {
        log.info("Fetching notifications for userId={}, page={}, size={}, sortBy={}, sortDir={}", userId, page, size, sortBy, sortDir);

        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String sortField = (sortBy != null && !sortBy.isBlank()) ? sortBy : "createdAt";
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortField));

        Specification<Notification> spec = buildSpecification(userId, search, read, priority, type);

        Page<Notification> notificationPage = notificationRepository.findAll(spec, pageable);

        List<NotificationDTO> content = notificationPage.getContent().stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        PageResponse<NotificationDTO> response = new PageResponse<>();
        response.setContent(content);
        response.setPageNumber(notificationPage.getNumber());
        response.setPageSize(notificationPage.getSize());
        response.setTotalElements(notificationPage.getTotalElements());
        response.setTotalPages(notificationPage.getTotalPages());
        response.setLast(notificationPage.isLast());

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationDTO> getRecentNotificationsByUserId(UUID userId, int limit) {
        log.info("Fetching recent notifications for userId={}, limit={}", userId, limit);
        List<Notification> notifications = notificationRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId);

        return notifications.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public NotificationDTO markAsRead(UUID id) {
        log.info("Marking notification as read: id={}", id);
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Notification not found with id: " + id));

        notification.setIsRead(true);
        notification.setReadAt(LocalDateTime.now());

        Notification saved = notificationRepository.save(notification);

        // Update unread count cache
        unreadCountCacheService.decrement(notification.getUserId());

        return toDto(saved);
    }

    @Override
    @Transactional
    public int markAllAsReadByUserId(UUID userId) {
        log.info("Marking all notifications as read for userId={}", userId);
        int count = notificationRepository.markAllAsReadByUserId(userId);

        // Update unread count cache
        unreadCountCacheService.setZero(userId);

        return count;
    }

    @Override
    @Transactional
    public long clearAllByUserId(UUID userId) {
        log.info("Clearing all notifications for userId={}", userId);
        int count = notificationRepository.deleteAllByUserId(userId);

        // Update unread count cache
        unreadCountCacheService.setZero(userId);

        return count;
    }

    @Override
    @Transactional
    public void deleteNotification(UUID id) {
        log.info("Deleting notification: id={}", id);
        if (!notificationRepository.existsById(id)) {
            throw new EntityNotFoundException("Notification not found with id: " + id);
        }
        notificationRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnreadByUserId(UUID userId) {
        log.info("Counting unread notifications for userId={}", userId);
        // Try cache first
        long cached = unreadCountCacheService.getUnreadCount(userId);
        if (cached > 0) {
            return cached;
        }
        // Fallback to database
        return notificationRepository.countUnreadByUserId(userId);
    }

    @Override
    @Transactional
    public void notifyUser(UUID userId, String title, String message, String entityType, UUID entityId) {
        log.info("Sending notification to userId={}, title={}", userId, title);

        // Check for duplicates
        if (deduplicationService.isDuplicate(userId, entityType, entityId)) {
            log.info("Duplicate notification skipped for userId={}, entityType={}, entityId={}", userId, entityType, entityId);
            return;
        }

        createNotificationIfNotExists(userId, title, message, entityType, entityId);

        // Mark as processed
        deduplicationService.markProcessed(userId, entityType, entityId);
    }

    @Override
    @Transactional
    public void notifyUsers(List<UUID> userIds, String title, String message, String entityType, UUID entityId) {
        log.info("Sending notification to {} users, title={}", userIds.size(), title);
        for (UUID userId : userIds) {
            try {
                notifyUser(userId, title, message, entityType, entityId);
            } catch (Exception e) {
                log.error("Failed to send notification to userId={}: {}", userId, e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public void notifyRole(String role, String title, String message, String entityType, UUID entityId, String route) {
        log.info("Sending notification to role={}, title={}", role, title);
        List<User> users = userRepository.findAllByRole(User.UserRole.valueOf(role.toUpperCase()));
        List<UUID> userIds = users.stream().map(User::getId).toList();

        for (UUID userId : userIds) {
            try {
                createNotificationIfNotExists(userId, title, message, entityType, entityId);
                deduplicationService.markProcessed(userId, entityType, entityId);
            } catch (Exception e) {
                log.error("Failed to send notification to userId={}: {}", userId, e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public void notifyTopic(String topic, String title, String message, String entityType, UUID entityId, String route) {
        log.info("Sending notification to topic={}, title={}", topic, title);
        // For topic-based notifications, send push notification directly
        Map<String, String> data = new HashMap<>();
        data.put("entityType", entityType != null ? entityType : "");
        data.put("entityId", entityId != null ? entityId.toString() : "");
        data.put("route", route != null ? route : "");

        firebasePushService.pushToTopic(topic, title, message, data);
    }

    @Override
    @Transactional
    public void createNotificationIfNotExists(UUID userId, String title, String message, String entityType, UUID entityId) {
        log.info("Creating notification if not exists for userId={}, entityType={}, entityId={}", userId, entityType, entityId);

        // Check if already exists
        if (entityType != null && entityId != null &&
            notificationRepository.existsByUserIdAndEntityTypeAndEntityId(userId, entityType, entityId)) {
            log.info("Notification already exists for userId={}, entityType={}, entityId={}", userId, entityType, entityId);
            return;
        }

        // Create and save notification
        NotificationDTO dto = new NotificationDTO();
        dto.setUserId(userId);
        dto.setTitle(title);
        dto.setMessage(message);
        dto.setSeverity(Notification.Severity.NORMAL);
        dto.setType(Notification.NotificationType.INFO);
        dto.setIsRead(false);
        dto.setEntityType(entityType);
        dto.setEntityId(entityId);
        dto.setNotificationEventId(UUID.randomUUID());

        createNotification(dto);
    }

    @Override
    @Transactional
    public int retryDlqEvents(int limit) {
        log.info("Retrying DLQ events with limit={}", limit);
        // TODO: Implement DLQ retry logic
        return 0;
    }

    // Helper methods


    private Notification toEntity(NotificationDTO dto) {
        Notification notification = new Notification();
        notification.setId(dto.getId());
        notification.setUserId(dto.getUserId());
        notification.setTitle(dto.getTitle());
        notification.setMessage(dto.getMessage());
        notification.setSeverity(dto.getSeverity());
        notification.setType(dto.getType());
        notification.setIsRead(dto.getIsRead());
        notification.setEntityType(dto.getEntityType());
        notification.setEntityId(dto.getEntityId());
        notification.setNotificationEventId(dto.getNotificationEventId());
        notification.setRoute(dto.getRoute());
        notification.setReadAt(dto.getReadAt());
        notification.setCreatedAt(dto.getCreatedAt());
        notification.setScheduledAt(dto.getScheduledAt());
        notification.setDeliverAt(dto.getDeliverAt());
        notification.setStatus(dto.getStatus());
        return notification;
    }

    private NotificationDTO toDto(Notification notification) {
        NotificationDTO dto = new NotificationDTO();
        dto.setId(notification.getId());
        dto.setUserId(notification.getUserId());
        dto.setTitle(notification.getTitle());
        dto.setMessage(notification.getMessage());
        dto.setSeverity(notification.getSeverity());
        dto.setType(notification.getType());
        dto.setIsRead(notification.getIsRead());
        dto.setEntityType(notification.getEntityType());
        dto.setEntityId(notification.getEntityId());
        dto.setNotificationEventId(notification.getNotificationEventId());
        dto.setRoute(notification.getRoute());
        dto.setReadAt(notification.getReadAt());
        dto.setCreatedAt(notification.getCreatedAt());
        dto.setScheduledAt(notification.getScheduledAt());
        dto.setDeliverAt(notification.getDeliverAt());
        dto.setStatus(notification.getStatus());
        return dto;
    }

    private Specification<Notification> buildSpecification(UUID userId, String search, String read, String priority, String type) {
        return Specification.where(hasUserId(userId))
                .and(hasSearchTerm(search))
                .and(hasReadStatus(read))
                .and(hasPriority(priority))
                .and(hasType(type));
    }

    private Specification<Notification> hasUserId(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    private Specification<Notification> hasSearchTerm(String search) {
        if (search == null || search.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        String searchPattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), searchPattern),
                cb.like(cb.lower(root.get("message")), searchPattern)
        );
    }

    private Specification<Notification> hasReadStatus(String read) {
        if (read == null || read.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        Boolean isRead = Boolean.parseBoolean(read);
        return (root, query, cb) -> cb.equal(root.get("isRead"), isRead);
    }

    private Specification<Notification> hasPriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        try {
            Notification.Severity severity = Notification.Severity.valueOf(priority.toUpperCase());
            return (root, query, cb) -> cb.equal(root.get("severity"), severity);
        } catch (IllegalArgumentException e) {
            return (root, query, cb) -> cb.conjunction();
        }
    }

    private Specification<Notification> hasType(String type) {
        if (type == null || type.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        try {
            Notification.NotificationType notificationType = Notification.NotificationType.valueOf(type.toUpperCase());
            return (root, query, cb) -> cb.equal(root.get("type"), notificationType);
        } catch (IllegalArgumentException e) {
            return (root, query, cb) -> cb.conjunction();
        }
    }
}
