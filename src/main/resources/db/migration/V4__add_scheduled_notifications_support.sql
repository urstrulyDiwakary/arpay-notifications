-- ==========================================================================
-- ARPay Notifications Service - Scheduled Notifications Support
-- Adds: scheduled_at, deliver_at, status columns to notifications table
-- ==========================================================================

-- Add scheduled_at column for when the notification should be delivered
ALTER TABLE notifications
ADD COLUMN scheduled_at TIMESTAMP,
ADD COLUMN deliver_at TIMESTAMP,
ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SENT';

-- Add status constraint
ALTER TABLE notifications
ADD CONSTRAINT chk_notification_status CHECK (status IN ('PENDING', 'SCHEDULED', 'SENT', 'FAILED', 'CANCELLED'));

-- Set default for existing records
UPDATE notifications SET status = 'SENT' WHERE status IS NULL;
UPDATE notifications SET scheduled_at = created_at WHERE scheduled_at IS NULL;

-- Add indexes for scheduled notification processing
CREATE INDEX IF NOT EXISTS idx_notifications_status_scheduled ON notifications(status, scheduled_at)
WHERE status IN ('PENDING', 'SCHEDULED');

CREATE INDEX IF NOT EXISTS idx_notifications_scheduled_at ON notifications(scheduled_at)
WHERE status IN ('PENDING', 'SCHEDULED');

-- Comment on columns
COMMENT ON COLUMN notifications.scheduled_at IS 'Scheduled delivery time for the notification';
COMMENT ON COLUMN notifications.deliver_at IS 'Actual delivery timestamp when notification was sent';
COMMENT ON COLUMN notifications.status IS 'Notification delivery status: PENDING, SCHEDULED, SENT, FAILED, CANCELLED';

-- ==========================================================================
-- Verification queries
-- ==========================================================================

-- Check columns added
-- SELECT column_name, data_type, is_nullable, column_default
-- FROM information_schema.columns
-- WHERE table_name = 'notifications' AND column_name IN ('scheduled_at', 'deliver_at', 'status')
-- ORDER BY ordinal_position;

-- Check indexes
-- SELECT indexname, indexdef
-- FROM pg_indexes
-- WHERE tablename = 'notifications' AND indexname LIKE '%scheduled%'
-- ORDER BY indexname;
