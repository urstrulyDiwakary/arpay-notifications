package com.arpay.repository;

import com.arpay.entity.NotificationDeliveryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDeliveryLogRepository extends JpaRepository<NotificationDeliveryLog, UUID> {

    Optional<NotificationDeliveryLog> findByNotificationEventIdAndUserIdAndChannelAndTokenAndAttemptNumber(
            UUID notificationEventId,
            UUID userId,
            NotificationDeliveryLog.Channel channel,
            String token,
            Integer attemptNumber
    );

    Optional<NotificationDeliveryLog> findTopByNotificationEventIdAndUserIdIsNullAndChannelAndTokenOrderByCreatedAtDesc(
            UUID notificationEventId,
            NotificationDeliveryLog.Channel channel,
            String token
    );

    long countByChannelAndStatus(NotificationDeliveryLog.Channel channel, NotificationDeliveryLog.Status status);

    @Query("""
            SELECT COUNT(DISTINCT d.notificationEventId)
            FROM NotificationDeliveryLog d
            WHERE d.channel = :channel
              AND d.deliveredAt IS NOT NULL
              AND d.deliveredAt >= :from
            """)
    long countDistinctDeliveredNotificationIdsByChannelSince(
            @Param("channel") NotificationDeliveryLog.Channel channel,
            @Param("from") LocalDateTime from
    );

    @Query("""
            SELECT COUNT(DISTINCT d.notificationEventId)
            FROM NotificationDeliveryLog d
            WHERE d.channel = :channel
              AND d.status = :status
              AND d.createdAt >= :from
            """)
    long countDistinctNotificationIdsByChannelStatusSince(
            @Param("channel") NotificationDeliveryLog.Channel channel,
            @Param("status") NotificationDeliveryLog.Status status,
            @Param("from") LocalDateTime from
    );

    @Query("""
            SELECT COALESCE(AVG(d.queueDelayMs), 0)
            FROM NotificationDeliveryLog d
            WHERE d.processedAt IS NOT NULL
              AND d.queueDelayMs IS NOT NULL
              AND d.processedAt >= :from
            """)
    double avgQueueDelayMsSince(@Param("from") LocalDateTime from);

    /**
     * Count delivery logs created after the specified timestamp.
     * Used for calculating processing rate metrics.
     */
    long countByProcessedAtAfter(@Param("from") LocalDateTime from);
}
