package com.arpay.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeduplicationService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String DEDUP_KEY_PREFIX = "notif:dedup:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    /**
     * Check if notification is duplicate (already processed).
     * Note: This method has a race condition. Use checkAndMark() for atomic operation.
     */
    public boolean isDuplicate(UUID userId, String entityType, UUID entityId) {
        if (userId == null || entityType == null || entityId == null) {
            return false;
        }
        String key = buildDedupKey(userId, entityType, entityId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * Mark notification as processed (store dedup key).
     * Note: This method has a race condition. Use checkAndMark() for atomic operation.
     */
    public void markProcessed(UUID userId, String entityType, UUID entityId) {
        markProcessed(userId, entityType, entityId, DEFAULT_TTL);
    }

    /**
     * Mark notification as processed with custom TTL.
     * Note: This method has a race condition. Use checkAndMark() for atomic operation.
     */
    public void markProcessed(UUID userId, String entityType, UUID entityId, Duration ttl) {
        if (userId == null || entityType == null || entityId == null) {
            return;
        }
        String key = buildDedupKey(userId, entityType, entityId);
        redisTemplate.opsForValue().set(key, "1", ttl);
        log.debug("Dedup key stored: {}", key);
    }

    /**
     * Atomic check-and-mark operation using Redis SETNX.
     * This prevents race conditions when multiple requests arrive simultaneously.
     * 
     * @return true if this is the first occurrence (not duplicate), false if duplicate
     */
    public boolean checkAndMark(UUID userId, String entityType, UUID entityId) {
        return checkAndMark(userId, entityType, entityId, DEFAULT_TTL);
    }

    /**
     * Atomic check-and-mark operation with custom TTL.
     * Uses Redis SETNX (SET if Not eXists) for atomicity.
     * 
     * @return true if this is the first occurrence (not duplicate), false if duplicate
     */
    public boolean checkAndMark(UUID userId, String entityType, UUID entityId, Duration ttl) {
        if (userId == null || entityType == null || entityId == null) {
            return true;
        }
        String key = buildDedupKey(userId, entityType, entityId);
        Boolean wasAbsent = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
        boolean result = Boolean.TRUE.equals(wasAbsent);
        
        if (result) {
            log.debug("Dedup key set (first occurrence): {}", key);
        } else {
            log.debug("Duplicate detected (key already exists): {}", key);
        }
        
        return result;
    }

    private String buildDedupKey(UUID userId, String entityType, UUID entityId) {
        return DEDUP_KEY_PREFIX + userId + ":" + entityType + ":" + entityId;
    }
}
