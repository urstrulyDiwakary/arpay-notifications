package com.arpay.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UnreadCountCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String UNREAD_KEY_PREFIX = "notif:unread:";

    /**
     * Get unread count for user from cache
     */
    public long getUnreadCount(UUID userId) {
        if (userId == null) {
            return 0;
        }
        String key = buildUnreadKey(userId);
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Increment unread count
     */
    public void increment(UUID userId) {
        if (userId == null) {
            return;
        }
        String key = buildUnreadKey(userId);
        redisTemplate.opsForValue().increment(key);
        log.debug("Unread count incremented for user {}", userId);
    }

    /**
     * Decrement unread count
     */
    public void decrement(UUID userId) {
        if (userId == null) {
            return;
        }
        String key = buildUnreadKey(userId);
        Long current = redisTemplate.opsForValue().decrement(key);
        if (current != null && current < 0) {
            redisTemplate.delete(key);
        }
        log.debug("Unread count decremented for user {}", userId);
    }

    /**
     * Set unread count to zero
     */
    public void setZero(UUID userId) {
        if (userId == null) {
            return;
        }
        String key = buildUnreadKey(userId);
        redisTemplate.delete(key);
        log.debug("Unread count reset for user {}", userId);
    }

    /**
     * Set unread count to specific value
     */
    public void set(UUID userId, long count) {
        if (userId == null) {
            return;
        }
        String key = buildUnreadKey(userId);
        if (count > 0) {
            redisTemplate.opsForValue().set(key, count);
        } else {
            redisTemplate.delete(key);
        }
    }

    private String buildUnreadKey(UUID userId) {
        return UNREAD_KEY_PREFIX + userId;
    }
}
