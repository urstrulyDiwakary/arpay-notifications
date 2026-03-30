package com.arpay.repository;

import com.arpay.entity.NotificationEventState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationEventStateRepository extends JpaRepository<NotificationEventState, UUID> {

    Optional<NotificationEventState> findByEventId(UUID eventId);

    boolean existsByEventId(UUID eventId);

    @Modifying
    @Query("UPDATE NotificationEventState n SET n.state = :state, n.updatedAt = CURRENT_TIMESTAMP WHERE n.eventId = :eventId")
    void updateStateByEventId(@Param("eventId") UUID eventId, @Param("state") NotificationEventState.State state);

    @Query("SELECT n FROM NotificationEventState n WHERE n.state = :state AND n.createdAt < :threshold")
    List<NotificationEventState> findByStateAndCreatedAtBefore(
            @Param("state") NotificationEventState.State state,
            @Param("threshold") LocalDateTime threshold
    );

    long countByState(NotificationEventState.State state);

    long countBySlaStatus(NotificationEventState.SlaStatus slaStatus);
}
