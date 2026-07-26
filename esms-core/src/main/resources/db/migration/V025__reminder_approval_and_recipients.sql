-- ─── Reminder approval workflow ─────────────────────────────────────────────
-- Reminders now require a one-tier approval before they're eligible to fire.
-- Unlike campaigns, reminders are created fully-formed in a single request
-- (no drafting/editing phase), so there's no DRAFT state here — a reminder
-- starts PENDING_APPROVAL and an approver either approves it (-> APPROVED,
-- eligible for the scheduler) or rejects it (-> CANCELLED). Reminders never
-- go through the CEO/delegate tier, regardless of the workspace's delegation
-- setting — see the trigger rewrite below.

-- Existing reminders predate the approval step — grandfather them straight to
-- APPROVED so they keep firing exactly as before instead of getting stranded.
ALTER TABLE schedule DROP CONSTRAINT IF EXISTS schedule_status_check;
UPDATE schedule SET status = 'APPROVED' WHERE status = 'PENDING';

ALTER TABLE schedule
    ALTER COLUMN status SET DEFAULT 'PENDING_APPROVAL',
    ADD CONSTRAINT schedule_status_check
        CHECK (status IN ('PENDING_APPROVAL','APPROVED','FIRED','CANCELLED'));

-- ─── Approval table: allow reminder approvals alongside campaign approvals ──

ALTER TABLE approval
    ALTER COLUMN campaign_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS reminder_id UUID REFERENCES schedule(id);

ALTER TABLE approval
    ADD CONSTRAINT chk_approval_one_target
        CHECK ((campaign_id IS NOT NULL AND reminder_id IS NULL)
            OR (campaign_id IS NULL AND reminder_id IS NOT NULL));

CREATE INDEX IF NOT EXISTS idx_approval_reminder ON approval (reminder_id);

-- ─── Message: link sent messages back to the reminder that generated them ──
-- Campaigns already have message.campaign_id; reminders had no equivalent,
-- so a fired reminder's recipients could never be queried after the fact.

ALTER TABLE message ADD COLUMN IF NOT EXISTS reminder_id UUID REFERENCES schedule(id);

CREATE INDEX IF NOT EXISTS idx_message_reminder ON message (reminder_id);

-- ─── Approval tier: driven by the DELEGATION workspace feature, not kind ───
-- allowed_transition was keyed by workspace_kind (department tag), so only
-- the one seeded workspace with kind='FINANCE' ever got the two-tier chain.
-- Every real workspace created through the app defaults to kind='GENERIC',
-- so a workspace admin turning on the "delegation" feature had no effect on
-- approval tiering at all — campaigns there silently took the one-tier path
-- and skipped delegate/CEO approval. The column is repurposed here to hold
-- a tier name ('ONE_TIER' / 'TWO_TIER') instead of a workspace kind; the
-- trigger below derives the tier from workspace_permission at check time
-- (campaigns only — reminders always use ONE_TIER, see below).

DELETE FROM allowed_transition;

INSERT INTO allowed_transition (workspace_kind, from_state, to_state) VALUES
('ONE_TIER', 'DRAFT',            'PENDING_APPROVAL'),
('ONE_TIER', 'PENDING_APPROVAL', 'APPROVED'),
('ONE_TIER', 'PENDING_APPROVAL', 'DRAFT'),
('ONE_TIER', 'PENDING_APPROVAL', 'CANCELLED'),
('ONE_TIER', 'APPROVED',         'QUEUED'),
('ONE_TIER', 'QUEUED',           'COMPLETED'),
('ONE_TIER', 'DRAFT',            'CANCELLED'),
('ONE_TIER', 'APPROVED',         'CANCELLED'),
('ONE_TIER', 'QUEUED',           'CANCELLED'),

('TWO_TIER', 'DRAFT',         'PENDING_HEAD'),
('TWO_TIER', 'PENDING_HEAD',  'PENDING_CEO'),
('TWO_TIER', 'PENDING_HEAD',  'DRAFT'),
('TWO_TIER', 'PENDING_CEO',   'APPROVED'),
('TWO_TIER', 'PENDING_CEO',   'DRAFT'),
('TWO_TIER', 'APPROVED',      'QUEUED'),
('TWO_TIER', 'QUEUED',        'COMPLETED'),
('TWO_TIER', 'DRAFT',         'CANCELLED'),
('TWO_TIER', 'PENDING_HEAD',  'CANCELLED'),
('TWO_TIER', 'PENDING_CEO',   'CANCELLED'),
('TWO_TIER', 'APPROVED',      'CANCELLED'),
('TWO_TIER', 'QUEUED',        'CANCELLED');

CREATE OR REPLACE FUNCTION check_approval_transition()
RETURNS TRIGGER AS $$
DECLARE
    ws_tier VARCHAR(10);
BEGIN
    IF NEW.reminder_id IS NOT NULL THEN
        -- Reminders never go through the CEO/delegate tier, regardless of
        -- whether the workspace has delegation enabled.
        ws_tier := 'ONE_TIER';
    ELSE
        SELECT CASE WHEN EXISTS (
            SELECT 1 FROM workspace_permission
            WHERE workspace_id = NEW.workspace_id AND permission_code = 'DELEGATION'
        ) THEN 'TWO_TIER' ELSE 'ONE_TIER' END INTO ws_tier;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM allowed_transition
        WHERE workspace_kind = ws_tier
        AND from_state = NEW.from_state
        AND to_state   = NEW.to_state
    ) THEN
        RAISE EXCEPTION 'Illegal approval transition % → % for workspace %',
            NEW.from_state, NEW.to_state, NEW.workspace_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ─── New permission: approve/reject reminders ───────────────────────────────
-- Submitting isn't a separate step (see note above) so no SCHEDULE_SUBMIT is
-- needed — SCHEDULE_MANAGE already covers create. Approval is split out so a
-- future role split (e.g. an operator drafting, a dept head approving) is
-- possible without another migration.

INSERT INTO permission (id, code, category, description) VALUES
(uuid_generate_v4(), 'SCHEDULE_APPROVE', 'SCHEDULE', 'Approve or reject reminders');

INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000001', id FROM permission
WHERE code = 'SCHEDULE_APPROVE';

INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000002', id FROM permission
WHERE code = 'SCHEDULE_APPROVE';
