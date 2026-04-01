package com.arpay.repository;

import com.arpay.entity.User;
import com.arpay.entity.UserDeviceToken;
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
public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, UUID> {
    
    /**
     * Find all inactive tokens
     */
    List<UserDeviceToken> findAllByIsActiveFalse();
    
    /**
     * Find all active device tokens for a user
     */
    List<UserDeviceToken> findByUserIdAndIsActiveTrue(UUID userId);
    
    /**
     * Find a specific device token by token value.
     * Returns a List to safely handle duplicate tokens (should be unique but may have legacy duplicates).
     * Use findFirstByDeviceTokenOrderByCreatedAtDesc for single-result lookups.
     */
    List<UserDeviceToken> findByDeviceToken(String deviceToken);

    /**
     * Find the most recently created token entry for a given token value.
     * Safe to use even when duplicates exist.
     */
    Optional<UserDeviceToken> findFirstByDeviceTokenOrderByCreatedAtDesc(String deviceToken);
    
    /**
     * Check if user has any active device tokens
     */
    @SuppressWarnings("unused")
    boolean existsByUserIdAndIsActiveTrue(UUID userId);
    
    /**
     * Find all active tokens for users with a specific role
     */
    @SuppressWarnings("unused")
    @Query("SELECT udt FROM UserDeviceToken udt " +
           "JOIN User u ON udt.userId = u.id " +
           "WHERE u.role = :role AND udt.isActive = true")
    List<UserDeviceToken> findActiveTokensByUserRole(@Param("role") User.UserRole role);
    
    /**
     * Find tokens that haven't been used recently (for cleanup)
     */
    @SuppressWarnings("unused")
    @Query("SELECT udt FROM UserDeviceToken udt " +
           "WHERE udt.lastUsedAt < :threshold AND udt.isActive = true")
    List<UserDeviceToken> findStaleTokens(@Param("threshold") LocalDateTime threshold);
    
    /**
     * Find tokens with high FCM error count
     */
    @SuppressWarnings("unused")
    @Query("SELECT udt FROM UserDeviceToken udt " +
           "WHERE udt.fcmErrorCount >= :threshold AND udt.isActive = true")
    List<UserDeviceToken> findHighErrorTokens(@Param("threshold") int threshold);
    
    /**
     * Count active tokens by user
     */
    @SuppressWarnings("unused")
    long countByUserIdAndIsActiveTrue(UUID userId);
    
    /**
     * Deactivate all tokens for a user (logout from all devices)
     */
    @Modifying
    @Query("UPDATE UserDeviceToken udt SET udt.isActive = false, " +
           "udt.invalidatedAt = CURRENT_TIMESTAMP, udt.invalidationReason = :reason " +
           "WHERE udt.userId = :userId")
    int deactivateAllTokensForUser(@Param("userId") UUID userId, @Param("reason") String reason);
    
    /**
     * Bulk deactivate tokens by token values
     */
    @SuppressWarnings("unused")
    @Modifying
    @Query("UPDATE UserDeviceToken udt SET udt.isActive = false, " +
           "udt.invalidatedAt = CURRENT_TIMESTAMP, udt.invalidationReason = :reason " +
           "WHERE udt.deviceToken IN :tokens")
    int deactivateTokens(@Param("tokens") List<String> tokens, @Param("reason") String reason);
    
    /**
     * Cleanup old invalidated tokens
     */
    @Modifying
    @Query("DELETE FROM UserDeviceToken udt " +
           "WHERE udt.isActive = false AND udt.invalidatedAt < :threshold")
    int deleteOldInvalidatedTokens(@Param("threshold") LocalDateTime threshold);
}
