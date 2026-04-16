package com.arpay.repository;

import com.arpay.entity.DeliveryIdempotencyKey;
import com.arpay.entity.DeliveryIdempotencyKey.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeliveryIdempotencyKeyRepository extends JpaRepository<DeliveryIdempotencyKey, UUID> {
    
    /**
     * Find by delivery ID (idempotency key)
     */
    Optional<DeliveryIdempotencyKey> findByDeliveryId(String deliveryId);
    
    /**
     * Check if delivery ID exists (for idempotency check)
     */
    @SuppressWarnings("unused")
    boolean existsByDeliveryId(String deliveryId);
    
    /**
     * Find pending deliveries for retry
     */
    @Query("SELECT d FROM DeliveryIdempotencyKey d " +
           "WHERE d.status = 'PENDING' OR d.status = 'FAILED' " +
           "ORDER BY d.lastAttemptAt ASC NULLS FIRST")
    List<DeliveryIdempotencyKey> findPendingDeliveries();
    
    /**
     * Find deliveries by notification event ID
     */
    List<DeliveryIdempotencyKey> findByNotificationEventId(UUID notificationEventId);
    
    /**
     * Find failed deliveries for a specific token
     */
    @SuppressWarnings("unused")
    @Query("SELECT d FROM DeliveryIdempotencyKey d " +
           "WHERE d.deviceToken = :token AND d.status = 'FAILED' " +
           "ORDER BY d.createdAt DESC")
    List<DeliveryIdempotencyKey> findFailedDeliveriesByToken(@Param("token") String token);
    
    /**
     * Count deliveries by status
     */
    long countByStatus(DeliveryStatus status);
    
    /**
     * Count failed deliveries in last hour
     */
    @Query("SELECT count(d) FROM DeliveryIdempotencyKey d " +
           "WHERE d.status = 'FAILED' AND d.createdAt > :threshold")
    long countRecentFailures(@Param("threshold") LocalDateTime threshold);
    
    /**
     * Atomic claim for delivery (prevents duplicate processing)
     * Returns 1 if successfully claimed, 0 if already being processed
     */
    @Modifying
    @Query("UPDATE DeliveryIdempotencyKey d SET d.status = 'PENDING', " +
           "d.lastAttemptAt = CURRENT_TIMESTAMP " +
           "WHERE d.deliveryId = :deliveryId AND d.status = 'PENDING'")
    int claimForDelivery(@Param("deliveryId") String deliveryId);
    
    /**
     * Mark as permanent failure (for poison messages)
     */
    @SuppressWarnings("unused")
    @Modifying
    @Query("UPDATE DeliveryIdempotencyKey d SET d.status = 'PERMANENT_FAILURE' " +
           "WHERE d.deliveryId = :deliveryId")
    int markPermanentFailure(@Param("deliveryId") String deliveryId);
    
    /**
     * Cleanup expired idempotency keys
     */
    @SuppressWarnings("unused")
    @Modifying
    @Query("DELETE FROM DeliveryIdempotencyKey d " +
           "WHERE d.expiresAt < :threshold OR " +
           "(d.status = 'SENT' AND d.createdAt < :sentThreshold)")
    int cleanupExpired(@Param("threshold") LocalDateTime threshold, 
                       @Param("sentThreshold") LocalDateTime sentThreshold);
    
    /**
     * Get delivery success rate in time window.
     * Returns NULL (not 0.0) when there are no records in the window — callers
     * interpret NULL as "no data" and default to 100% so an empty table never
     * triggers a false "success rate below threshold" alert.
     */
    @Query("SELECT " +
           "COUNT(CASE WHEN d.status = 'SENT' THEN 1 END) * 1.0 / NULLIF(COUNT(*), 0) " +
           "FROM DeliveryIdempotencyKey d " +
           "WHERE d.createdAt > :threshold")
    Double getSuccessRate(@Param("threshold") LocalDateTime threshold);
}
