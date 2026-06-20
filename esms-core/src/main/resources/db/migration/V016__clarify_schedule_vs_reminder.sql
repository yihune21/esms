-- ===================================================================
-- V016: Clarify Schedule vs Reminder
--
-- The `schedule` table is now used exclusively for "reminders":
--   Upload an Excel of insurance holders with expiry dates.
--   The system checks daily and sends an SMS to any holder whose
--   insurance expires in exactly `trigger_days` days.
--
-- The `campaign` table (kind=SCHEDULED) handles "send an SMS at a
--   specific future date/time" via the `scheduled_at` column.
-- ===================================================================

-- ── 1. Add trigger_days to the schedule (reminder) table ──────────
ALTER TABLE schedule
    ADD COLUMN IF NOT EXISTS trigger_days INTEGER NOT NULL DEFAULT 15;

COMMENT ON COLUMN schedule.trigger_days IS
    'Number of days before insurance expiry to trigger the reminder SMS. '
    'E.g. 15 = send SMS when exactly 15 days remain before policy expires.';

-- ── 2. Add index to help the daily poller find ACTIVE reminders ───
CREATE INDEX IF NOT EXISTS idx_schedule_active_status ON schedule (status)
    WHERE status = 'ACTIVE';

-- ── 3. Ensure campaign SCHEDULED poller index exists ─────────────
-- Campaigns with kind=SCHEDULED and status=APPROVED where scheduled_at is due
CREATE INDEX IF NOT EXISTS idx_campaign_scheduled_due
    ON campaign (scheduled_at, status)
    WHERE kind = 'SCHEDULED' AND status = 'APPROVED';

-- ── 4. Update campaign kind constraint: REMINDER is now its own feature ───
-- Reminders are managed via /reminders (the `schedule` table).
-- Campaigns now only support INSTANT (send now) or SCHEDULED (send at future date).
ALTER TABLE campaign
    DROP CONSTRAINT IF EXISTS campaign_kind_check;

ALTER TABLE campaign
    ADD CONSTRAINT campaign_kind_check
        CHECK (kind IN ('INSTANT', 'SCHEDULED'));
