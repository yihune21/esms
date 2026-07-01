ALTER TABLE schedule
    ADD COLUMN IF NOT EXISTS trigger_days INTEGER NOT NULL DEFAULT 15;

COMMENT ON COLUMN schedule.trigger_days IS
    'Number of days before insurance expiry to trigger the reminder SMS. '
    'E.g. 15 = send SMS when exactly 15 days remain before policy expires.';

CREATE INDEX IF NOT EXISTS idx_schedule_active_status ON schedule (status)
    WHERE status = 'ACTIVE';


CREATE INDEX IF NOT EXISTS idx_campaign_scheduled_due
    ON campaign (scheduled_at, status)
    WHERE kind = 'SCHEDULED' AND status = 'APPROVED';


ALTER TABLE campaign
    DROP CONSTRAINT IF EXISTS campaign_kind_check;

ALTER TABLE campaign
    ADD CONSTRAINT campaign_kind_check
        CHECK (kind IN ('INSTANT', 'SCHEDULED'));
