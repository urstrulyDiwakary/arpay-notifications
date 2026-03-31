-- ==========================================================================
-- ARPay Notifications Service - Idempotent Delivery Tracking
-- Creates: delivery_idempotency_keys table for exactly-once delivery semantics
-- ==========================================================================

-- ==========================================================================
-- Delivery Idempotency Keys Table
-- Prevents duplicate deliveries when workers crash and retry
-- ==========================================================================

CREATE TABLE IF NOT EXISTS delivery_idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id VARCHAR(100) NOT NULL UNIQUE,
    notification_event_id UUID NOT NULL,
    notification_id UUID NOT NULL,
    device_token VARCHAR(500) NOT NULL,
    device_token_hash VARCHAR(64),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    fcm_message_id VARCHAR(200),
    fcm_error_code VARCHAR(50),
    error_message TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 1,
    last_attempt_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    expires_at TIMESTAMP,
    
    CONSTRAINT chk_delivery_status CHECK (status IN (
        'PENDING', 'SENT', 'FAILED', 'PERMANENT_FAILURE', 'DUPLICATE'
    )),
    CONSTRAINT chk_attempt_count CHECK (attempt_count >= 0)
);

-- Indexes for delivery_idempotency_keys
CREATE INDEX IF NOT EXISTS idx_idempotency_delivery_id ON delivery_idempotency_keys(delivery_id);
CREATE INDEX IF NOT EXISTS idx_idempotency_event_id ON delivery_idempotency_keys(notification_event_id);
CREATE INDEX IF NOT EXISTS idx_idempotency_token ON delivery_idempotency_keys(device_token, created_at);
CREATE INDEX IF NOT EXISTS idx_idempotency_status ON delivery_idempotency_keys(status, created_at);
CREATE INDEX IF NOT EXISTS idx_idempotency_pending ON delivery_idempotency_keys(created_at) WHERE status IN ('PENDING', 'FAILED');

-- Comment on table and columns
COMMENT ON TABLE delivery_idempotency_keys IS 'Idempotency tracking for notification delivery - prevents duplicate pushes';
COMMENT ON COLUMN delivery_idempotency_keys.delivery_id IS 'Unique delivery ID: {notificationEventId}:{tokenHash}';
COMMENT ON COLUMN delivery_idempotency_keys.status IS 'PENDING, SENT, FAILED, PERMANENT_FAILURE, DUPLICATE';
COMMENT ON COLUMN delivery_idempotency_keys.device_token_hash IS 'SHA-256 hash of token (for debugging without exposing token)';
COMMENT ON COLUMN delivery_idempotency_keys.attempt_count IS 'Number of delivery attempts (max 3)';
COMMENT ON COLUMN delivery_idempotency_keys.expires_at IS 'When this idempotency key expires (default 24h)';

-- ==========================================================================
-- Update NotificationOutboxRepository to use SKIP LOCKED
-- This is done in Java code, but document the PostgreSQL behavior here
-- ==========================================================================

-- PostgreSQL row-level locking with SKIP LOCKED:
-- SELECT ... FOR UPDATE SKIP LOCKED
-- 
-- Behavior:
-- - Worker 1 picks entry ID=1, locks it
-- - Worker 2 tries to pick ID=1, SKIP LOCKED skips it, picks ID=2 instead
-- - No duplicate processing!
--
-- This is implemented via @Lock(PESSIMISTIC_WRITE) in Spring Data JPA
-- PostgreSQL dialect automatically adds SKIP LOCKED

-- ==========================================================================
-- Cleanup old idempotency keys (run periodically)
-- ==========================================================================

-- Delete expired keys (older than 24 hours)
-- DELETE FROM delivery_idempotency_keys 
-- WHERE expires_at < CURRENT_TIMESTAMP 
--    OR (status = 'SENT' AND created_at < CURRENT_TIMESTAMP - INTERVAL '24 hours');

-- ==========================================================================
-- Monitoring queries
-- ==========================================================================

-- Delivery success rate (last hour)
-- SELECT 
--     COUNT(CASE WHEN status = 'SENT' THEN 1 END) * 100.0 / COUNT(*) as success_rate_pct
-- FROM delivery_idempotency_keys 
-- WHERE created_at > CURRENT_TIMESTAMP - INTERVAL '1 hour';

-- Duplicate detection rate
-- SELECT 
--     COUNT(CASE WHEN status = 'DUPLICATE' THEN 1 END) as duplicate_count,
--     COUNT(*) as total_count
-- FROM delivery_idempotency_keys 
-- WHERE created_at > CURRENT_TIMESTAMP - INTERVAL '1 hour';

-- Permanent failure analysis (for token cleanup)
-- SELECT 
--     fcm_error_code,
--     COUNT(*) as failure_count
-- FROM delivery_idempotency_keys 
-- WHERE status = 'PERMANENT_FAILURE'
-- GROUP BY fcm_error_code
-- ORDER BY failure_count DESC;
