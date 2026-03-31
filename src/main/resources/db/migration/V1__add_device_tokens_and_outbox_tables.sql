-- ==========================================================================
-- ARPay Notifications Service - New Tables for Production Features
-- Creates: user_device_tokens, notification_outbox
-- ==========================================================================

-- ==========================================================================
-- User Device Tokens Table (supports multiple devices per user)
-- ==========================================================================

CREATE TABLE IF NOT EXISTS user_device_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    device_token VARCHAR(500) NOT NULL,
    device_type VARCHAR(20),
    app_version VARCHAR(50),
    platform_version VARCHAR(50),
    app_identifier VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT true,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    invalidated_at TIMESTAMP,
    invalidation_reason VARCHAR(100),
    fcm_error_message TEXT,
    fcm_error_count INTEGER NOT NULL DEFAULT 0,
    
    CONSTRAINT chk_device_type CHECK (device_type IN ('ANDROID', 'IOS', 'WEB') OR device_type IS NULL),
    CONSTRAINT chk_fcm_error_count CHECK (fcm_error_count >= 0)
);

-- Indexes for user_device_tokens
CREATE INDEX IF NOT EXISTS idx_user_tokens_user_id ON user_device_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_user_tokens_active ON user_device_tokens(user_id, is_active) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_user_tokens_token ON user_device_tokens(device_token);
CREATE INDEX IF NOT EXISTS idx_user_tokens_invalidated ON user_device_tokens(invalidated_at) WHERE is_active = false;

-- Comment on table and columns
COMMENT ON TABLE user_device_tokens IS 'Stores FCM device tokens for users - supports multiple devices per user';
COMMENT ON COLUMN user_device_tokens.user_id IS 'Reference to users table (UUID)';
COMMENT ON COLUMN user_device_tokens.device_token IS 'FCM device registration token';
COMMENT ON COLUMN user_device_tokens.device_type IS 'Device platform: ANDROID, IOS, WEB';
COMMENT ON COLUMN user_device_tokens.is_active IS 'Whether this token is currently active';
COMMENT ON COLUMN user_device_tokens.fcm_error_count IS 'Consecutive FCM error count for this token';
COMMENT ON COLUMN user_device_tokens.invalidation_reason IS 'Reason for token invalidation: EXPIRED, UNREGISTERED, ERROR, USER_LOGOUT';

-- ==========================================================================
-- Notification Outbox Table (transactional outbox pattern)
-- ==========================================================================

CREATE TABLE IF NOT EXISTS notification_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL,
    notification_event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_payload TEXT NOT NULL,
    queue_name VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    stream_id VARCHAR(100),
    
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD_LETTER')),
    CONSTRAINT chk_retry_count CHECK (retry_count >= 0)
);

-- Indexes for notification_outbox
CREATE INDEX IF NOT EXISTS idx_outbox_status ON notification_outbox(status, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_event_type ON notification_outbox(event_type);
CREATE INDEX IF NOT EXISTS idx_outbox_notification_id ON notification_outbox(notification_id);
CREATE INDEX IF NOT EXISTS idx_outbox_event_id ON notification_outbox(notification_event_id);

-- Comment on table and columns
COMMENT ON TABLE notification_outbox IS 'Transactional outbox for reliable event publishing';
COMMENT ON COLUMN notification_outbox.notification_id IS 'Reference to notifications table';
COMMENT ON COLUMN notification_outbox.notification_event_id IS 'Unique event correlation ID';
COMMENT ON COLUMN notification_outbox.event_type IS 'Type of event: NOTIFICATION_CREATED, etc.';
COMMENT ON COLUMN notification_outbox.event_payload IS 'JSON payload of the event';
COMMENT ON COLUMN notification_outbox.status IS 'PENDING, PUBLISHED, FAILED, DEAD_LETTER';
COMMENT ON COLUMN notification_outbox.stream_id IS 'Redis Stream ID after publishing';

-- ==========================================================================
-- Optional: Migrate existing user device tokens to new table
-- ==========================================================================

-- This migration copies existing tokens from users table to user_device_tokens
-- Run this only after deploying the new code

-- INSERT INTO user_device_tokens (user_id, device_token, device_type, is_active, created_at, last_used_at)
-- SELECT id, device_token, 'ANDROID' as device_type, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
-- FROM users
-- WHERE device_token IS NOT NULL 
--   AND device_token != ''
--   AND NOT EXISTS (
--     SELECT 1 FROM user_device_tokens udt WHERE udt.user_id = users.id
--   );

-- ==========================================================================
-- Verification queries
-- ==========================================================================

-- Check table creation
-- SELECT table_name, column_name, data_type 
-- FROM information_schema.columns 
-- WHERE table_name IN ('user_device_tokens', 'notification_outbox')
-- ORDER BY table_name, ordinal_position;

-- Check indexes
-- SELECT indexname, indexdef 
-- FROM pg_indexes 
-- WHERE tablename IN ('user_device_tokens', 'notification_outbox')
-- ORDER BY tablename, indexname;
