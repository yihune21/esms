-- ─── Reminders: creation no longer requires approval ───────────────────────
-- V025 made every reminder start life PENDING_APPROVAL, which meant you
-- couldn't even edit or organize a reminder without an approver blessing it
-- first. Approval should only be requested when a reminder is actually about
-- to be sent (see ReminderService.activate(), now the "submit for approval"
-- step called from the frontend's Send for Approval action), not at creation
-- time — so reminders now start in DRAFT: freely editable/deletable, but not
-- eligible to fire until submitted and approved.

-- Any reminder currently sitting PENDING_APPROVAL predates this change and
-- was never actually approved yet — reset it to DRAFT so it isn't stranded
-- waiting on an approval step that, under the new model, was never requested.
UPDATE schedule SET status = 'DRAFT' WHERE status = 'PENDING_APPROVAL';

ALTER TABLE schedule DROP CONSTRAINT IF EXISTS schedule_status_check;
ALTER TABLE schedule
    ALTER COLUMN status SET DEFAULT 'DRAFT',
    ADD CONSTRAINT schedule_status_check
        CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','FIRED','CANCELLED'));

-- No allowed_transition changes needed: activate() (DRAFT/CANCELLED ->
-- PENDING_APPROVAL) writes no Approval row, so it isn't subject to the
-- check_approval_transition trigger. approve()/reject() (PENDING_APPROVAL ->
-- APPROVED/CANCELLED) are unchanged and already covered by the existing
-- ONE_TIER rows from V025.
