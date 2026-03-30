package com.arpay.repository;

import com.arpay.entity.NotificationDlqEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDlqEntryRepository extends JpaRepository<NotificationDlqEntry, UUID> {

    Optional<NotificationDlqEntry> findByEventId(UUID eventId);

    boolean existsByEventId(UUID eventId);

    @Query("SELECT n FROM NotificationDlqEntry n WHERE n.resolved = false ORDER BY n.createdAt ASC")
    List<NotificationDlqEntry> findUnresolved();

    /**
     * Find DLQ entries due for retry: unresolved AND nextRetryAt has passed (or is null).
     * Uses Pageable for correct LIMIT support instead of a broken @Param("limit").
     */
    @Query("SELECT n FROM NotificationDlqEntry n " +
           "WHERE n.resolved = false " +
           "AND (n.nextRetryAt IS NULL OR n.nextRetryAt <= :now) " +
           "ORDER BY n.createdAt ASC")
    Page<NotificationDlqEntry> findDueForRetry(@Param("now") LocalDateTime now, Pageable pageable);

    long countByResolvedFalse();

    @Modifying
    @Query("UPDATE NotificationDlqEntry n SET n.resolved = true, n.resolvedAt = CURRENT_TIMESTAMP WHERE n.eventId = :eventId")
    void markResolved(@Param("eventId") UUID eventId);

    /**
     * Find DLQ entries by resolved status
     */
    List<NotificationDlqEntry> findByResolved(Boolean resolved);

    /**
     * Find all DLQ entries (for admin endpoint)
     */
    @Query("SELECT n FROM NotificationDlqEntry n ORDER BY n.createdAt DESC")
    List<NotificationDlqEntry> findAll();
}
