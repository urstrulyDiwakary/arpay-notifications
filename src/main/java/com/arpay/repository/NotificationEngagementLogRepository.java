package com.arpay.repository;

import com.arpay.entity.NotificationEngagementLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationEngagementLogRepository extends JpaRepository<NotificationEngagementLog, UUID> {

    Optional<NotificationEngagementLog> findByNotificationEventIdAndUserIdAndAction(
            UUID notificationEventId,
            UUID userId,
            NotificationEngagementLog.Action action
    );

    boolean existsByNotificationEventIdAndUserIdAndAction(
            UUID notificationEventId,
            UUID userId,
            NotificationEngagementLog.Action action
    );
}
