package com.arpay.repository;

import com.arpay.entity.NotificationOutbox;
import com.arpay.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {
    
    /**
     * Find outbox entries by event ID
     */
    Optional<NotificationOutbox> findByNotificationEventId(UUID notificationEventId);
    
    /**
     * Find pending outbox entries for processing.
     * Uses native query with FOR UPDATE SKIP LOCKED to prevent worker blocking.
     * 
     * PostgreSQL: SELECT ... FOR UPDATE SKIP LOCKED
     * This ensures workers skip locked rows instead of waiting.
     */
    @Query(value = "SELECT * FROM notification_outbox no " +
                   "WHERE no.status = :status " +
                   "ORDER BY no.created_at ASC " +
                   "FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<NotificationOutbox> findPendingSkipLocked(@Param("status") String status);
    
    /**
     * Find pending outbox entries with limit using SKIP LOCKED
     */
    @Query(value = "SELECT * FROM notification_outbox no " +
                   "WHERE no.status = :status " +
                   "ORDER BY no.created_at ASC " +
                   "LIMIT :limit " +
                   "FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<NotificationOutbox> findPendingWithLimit(@Param("status") String status, 
                                                   @Param("limit") int limit);
    
    /**
     * Find failed outbox entries for retry
     */
    @Query("SELECT no FROM NotificationOutbox no " +
           "WHERE no.status = 'FAILED' AND no.retryCount < :maxRetries " +
           "ORDER BY no.lastAttemptAt ASC")
    List<NotificationOutbox> findDueForRetry(@Param("maxRetries") int maxRetries);
    
    /**
     * Count pending outbox entries
     */
    long countByStatus(OutboxStatus status);
    
    /**
     * Mark outbox as published
     */
    @Modifying
    @Query("UPDATE NotificationOutbox no SET no.status = 'PUBLISHED', " +
           "no.publishedAt = CURRENT_TIMESTAMP, no.streamId = :streamId " +
           "WHERE no.notificationEventId = :eventId")
    int markPublished(@Param("eventId") UUID eventId, @Param("streamId") String streamId);
    
    /**
     * Mark outbox as failed
     */
    @Modifying
    @Query("UPDATE NotificationOutbox no SET no.status = 'FAILED', " +
           "no.errorMessage = :error, no.lastAttemptAt = CURRENT_TIMESTAMP, " +
           "no.retryCount = no.retryCount + 1 " +
           "WHERE no.id = :id")
    int markFailed(@Param("id") UUID id, @Param("error") String error);
    
    /**
     * Find QUEUED outbox entries older than :threshold (stuck entries from crashed workers).
     * Used by startup recovery to re-dispatch entries that were claimed but never delivered.
     */
    @Query(value = "SELECT * FROM notification_outbox " +
                   "WHERE status = 'QUEUED' AND created_at < :threshold " +
                   "ORDER BY created_at ASC " +
                   "LIMIT :limit",
           nativeQuery = true)
    List<NotificationOutbox> findStuckQueued(@Param("threshold") LocalDateTime threshold,
                                              @Param("limit") int limit);

    /**
     * Delete old published outbox entries (cleanup)
     */
    @Modifying
    @Query("DELETE FROM NotificationOutbox no " +
           "WHERE no.status = 'PUBLISHED' AND no.publishedAt < :threshold")
    int deleteOldPublished(@Param("threshold") LocalDateTime threshold);
}
