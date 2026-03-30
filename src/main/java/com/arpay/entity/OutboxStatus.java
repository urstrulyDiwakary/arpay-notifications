package com.arpay.entity;

/**
 * Outbox status enum for transactional outbox pattern
 */
public enum OutboxStatus {
    /**
     * Event created but not yet dispatched to an async worker
     */
    PENDING,

    /**
     * Dispatched to async worker; delivery in progress.
     * If the JVM crashes while in this state, startup recovery re-dispatches the entry.
     */
    QUEUED,

    /**
     * Event delivered successfully (push sent via FCM)
     */
    PUBLISHED,
    
    /**
     * Event publishing failed, will be retried
     */
    FAILED,
    
    /**
     * Event publishing failed permanently, requires manual intervention
     */
    DEAD_LETTER
}
