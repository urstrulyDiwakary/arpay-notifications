-- ==========================================================================
-- ARPay Notifications Service - Architecture Fix: Outbox QUEUED Status + DLQ Backoff
-- ==========================================================================

-- Add QUEUED status to the notification_outbox status constraint.
-- QUEUED = event has been dispatched to the async worker but not yet delivered.
ALTER TABLE notification_outbox
    DROP CONSTRAINT IF EXISTS chk_outbox_status;

ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_status CHECK (
        status IN ('PENDING', 'QUEUED', 'PUBLISHED', 'FAILED', 'DEAD_LETTER')
    );

-- Add next_retry_at to notification_dlq for exponential backoff scheduling.
-- Null = eligible immediately (for existing rows created before this migration).
ALTER TABLE notification_dlq
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP;

-- Seed next_retry_at for existing unresolved DLQ entries so they are retried soon.
UPDATE notification_dlq
SET next_retry_at = COALESCE(last_retry_at, created_at)
    + (INTERVAL '1 minute' * LEAST((POWER(2, COALESCE(retry_count, 0))::BIGINT), 60))
WHERE resolved = false
  AND next_retry_at IS NULL;

-- Index for the DLQ retry scheduler to quickly find due entries.
CREATE INDEX IF NOT EXISTS idx_dlq_next_retry_at
    ON notification_dlq(next_retry_at)
    WHERE resolved = false;

-- Composite index that covers startup-recovery queries (PENDING + QUEUED outbox scans).
CREATE INDEX IF NOT EXISTS idx_outbox_recovery
    ON notification_outbox(status, created_at)
    WHERE status IN ('PENDING', 'QUEUED');

