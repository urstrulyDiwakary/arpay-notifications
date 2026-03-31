-- ==========================================================================
-- ARPay Notifications Service - Database Indexes for Production
-- Run this migration to add performance-critical indexes
-- ==========================================================================

-- Notifications table indexes
CREATE INDEX IF NOT EXISTS idx_notifications_user_id 
    ON notifications(user_id);

CREATE INDEX IF NOT EXISTS idx_notifications_created_at 
    ON notifications(created_at DESC);

-- Composite index for user's notifications sorted by date (most common query)
CREATE INDEX IF NOT EXISTS idx_notifications_user_created 
    ON notifications(user_id, created_at DESC);

-- Index for event correlation
CREATE INDEX IF NOT EXISTS idx_notifications_event_id 
    ON notifications(notification_event_id);

-- Index for entity-based lookups
CREATE INDEX IF NOT EXISTS idx_notifications_entity 
    ON notifications(entity_type, entity_id);

-- Partial index for unread notifications (faster unread counts)
CREATE INDEX IF NOT EXISTS idx_notifications_unread 
    ON notifications(user_id, is_read) 
    WHERE is_read = false;

-- Notification delivery log indexes
CREATE INDEX IF NOT EXISTS idx_delivery_log_notification_id 
    ON notification_delivery_log(notification_id);

CREATE INDEX IF NOT EXISTS idx_delivery_log_event_id 
    ON notification_delivery_log(notification_event_id);

-- Index for status-based queries (pending deliveries, failed deliveries)
CREATE INDEX IF NOT EXISTS idx_delivery_log_status 
    ON notification_delivery_log(status, created_at);

-- Index for user-specific delivery logs
CREATE INDEX IF NOT EXISTS idx_delivery_log_user 
    ON notification_delivery_log(user_id, created_at DESC);

-- DLQ table indexes
CREATE INDEX IF NOT EXISTS idx_dlq_resolved 
    ON notification_dlq(resolved, created_at);

CREATE INDEX IF NOT EXISTS idx_dlq_event_id 
    ON notification_dlq(event_id);

-- Index for retry scheduling
CREATE INDEX IF NOT EXISTS idx_dlq_retry 
    ON notification_dlq(resolved, last_retry_at) 
    WHERE resolved = false;

-- User device tokens table indexes (new table)
CREATE INDEX IF NOT EXISTS idx_user_tokens_user_id 
    ON user_device_tokens(user_id);

-- Partial index for active tokens only
CREATE INDEX IF NOT EXISTS idx_user_tokens_active 
    ON user_device_tokens(user_id, is_active) 
    WHERE is_active = true;

-- Unique index for token lookup
CREATE INDEX IF NOT EXISTS idx_user_tokens_token 
    ON user_device_tokens(device_token);

-- Index for cleanup operations
CREATE INDEX IF NOT EXISTS idx_user_tokens_invalidated 
    ON user_device_tokens(invalidated_at) 
    WHERE is_active = false;

-- Notification outbox table indexes (new table)
CREATE INDEX IF NOT EXISTS idx_outbox_status 
    ON notification_outbox(status, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_event_type 
    ON notification_outbox(event_type);

CREATE INDEX IF NOT EXISTS idx_outbox_notification_id 
    ON notification_outbox(notification_id);

-- Index for event-based lookups
CREATE INDEX IF NOT EXISTS idx_outbox_event_id 
    ON notification_outbox(notification_event_id);

-- ==========================================================================
-- Notification Event State table indexes (if table exists)
-- ==========================================================================

CREATE INDEX IF NOT EXISTS idx_event_state_state 
    ON notification_event_state(state, created_at);

CREATE INDEX IF NOT EXISTS idx_event_state_event_id 
    ON notification_event_state(event_id);

CREATE INDEX IF NOT EXISTS idx_event_state_sla 
    ON notification_event_state(sla_status, sla_deadline_at) 
    WHERE sla_status != 'MET';

-- ==========================================================================
-- Notification Engagement Log table indexes (if table exists)
-- ==========================================================================

CREATE INDEX IF NOT EXISTS idx_engagement_notification_id 
    ON notification_engagement_log(notification_id);

CREATE INDEX IF NOT EXISTS idx_engagement_user_id 
    ON notification_engagement_log(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_engagement_action 
    ON notification_engagement_log(action, created_at);

-- ==========================================================================
-- Analyze tables to update statistics
-- ==========================================================================

ANALYZE notifications;
ANALYZE notification_delivery_log;
ANALYZE notification_dlq;
ANALYZE user_device_tokens;
ANALYZE notification_outbox;
ANALYZE notification_event_state;
ANALYZE notification_engagement_log;
ANALYZE users;

-- ==========================================================================
-- Verification queries (run after migration)
-- ==========================================================================

-- Check all indexes on notifications table
-- SELECT indexname, indexdef 
-- FROM pg_indexes 
-- WHERE tablename = 'notifications' 
-- ORDER BY indexname;

-- Check index usage statistics (after some traffic)
-- SELECT schemaname, relname, indexrelname, idx_scan, idx_tup_read, idx_tup_fetch
-- FROM pg_stat_user_indexes
-- WHERE schemaname = 'public'
-- ORDER BY idx_scan DESC;
