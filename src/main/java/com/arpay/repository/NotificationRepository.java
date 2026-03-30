package com.arpay.repository;

import com.arpay.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    Page<Notification> findByUserId(UUID userId, Pageable pageable);

    Page<Notification> findByUserIdAndIsRead(UUID userId, Boolean isRead, Pageable pageable);

    Page<Notification> findBySeverity(Notification.Severity severity, Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.isRead = false")
    long countUnreadByUserId(@Param("userId") UUID userId);

    @Query("SELECT n FROM Notification n WHERE n.userId = :userId ORDER BY n.createdAt DESC")
    List<Notification> findRecentByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP WHERE n.userId = :userId AND n.isRead = false")
    int markAllAsReadByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    List<Notification> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Notification> findTop20ByUserIdAndIsReadFalseOrderByCreatedAtDesc(UUID userId);

    boolean existsByUserIdAndEntityTypeAndEntityId(UUID userId, String entityType, UUID entityId);

    Optional<Notification> findByUserIdAndEntityTypeAndEntityId(UUID userId, String entityType, UUID entityId);

    Optional<Notification> findByNotificationEventId(UUID notificationEventId);

    Optional<Notification> findByNotificationEventIdAndUserId(UUID notificationEventId, UUID userId);

    long countByUserIdAndCreatedAtAfter(UUID userId, LocalDateTime since);

    @Query("SELECT n.id FROM Notification n WHERE n.createdAt < :cutoff ORDER BY n.createdAt ASC")
    List<UUID> findIdsByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.id IN :ids")
    int deleteByIdIn(@Param("ids") List<UUID> ids);

    /**
     * Find scheduled notifications that are due for delivery
     */
    @Query("SELECT n FROM Notification n WHERE n.status = 'SCHEDULED' AND n.scheduledAt <= :now ORDER BY n.scheduledAt ASC")
    List<Notification> findDueScheduledNotifications(@Param("now") LocalDateTime now);
}
