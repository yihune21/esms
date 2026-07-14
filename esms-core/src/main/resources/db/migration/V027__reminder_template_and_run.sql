-- ─── Reminders split into templates and runs ───────────────────────────────
-- A reminder is really two things that were conflated in one `schedule` row:
--
--   1. A TEMPLATE — the reusable definition a user creates and manages: name,
--      message, and days-left rule. No approval; just active/inactive. Lives
--      in the new reminder_template table below.
--
--   2. A RUN — one actual send: "here's today's uploaded policy data, send it
--      using template X". Every run is a separate, approval-gated instance
--      (approval required each time, even if an earlier run of the same
--      template was approved) that auto-fires once approved. Runs keep living
--      in `schedule` (so message.reminder_id / approval.reminder_id / the
--      dispatch + delivery-stat machinery all keep working unchanged) — a
--      schedule row now means "a run", linked to its template.

-- ── The template table ──────────────────────────────────────────────────────
CREATE TABLE reminder_template (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id  UUID NOT NULL,
    name          VARCHAR(255) NOT NULL,
    custom_body   TEXT,
    template_id   UUID,                 -- optional message-template reference
    trigger_days  INTEGER NOT NULL,
    kind          VARCHAR(50) NOT NULL DEFAULT 'CUSTOM',
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','INACTIVE')),
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reminder_template_ws ON reminder_template (workspace_id);

-- ── schedule becomes the run ────────────────────────────────────────────────
ALTER TABLE schedule
    ADD COLUMN IF NOT EXISTS reminder_template_id UUID REFERENCES reminder_template(id),
    ADD COLUMN IF NOT EXISTS created_by UUID;

-- Carry every existing reminder over as a template so nothing configured under
-- the old model is lost. (Existing schedule rows were templates-in-disguise.)
INSERT INTO reminder_template (id, workspace_id, name, custom_body, template_id, trigger_days, kind, status, created_at)
SELECT id, workspace_id, name, custom_body, template_id, trigger_days, kind, 'ACTIVE', created_at
FROM schedule;

-- The old schedule rows themselves represented reminders, not runs — there are
-- no real runs yet under the new model, so clear them (and the now-dangling
-- reminder approvals / message links) rather than trying to reinterpret them.
-- Dev data only; message rows are kept, they just lose their old reminder link.
UPDATE message SET reminder_id = NULL WHERE reminder_id IS NOT NULL;
DELETE FROM approval WHERE reminder_id IS NOT NULL;
DELETE FROM schedule;

-- Runs use the campaign-style lifecycle (no DRAFT — a run is born already
-- submitted for approval when the user presses Send).
ALTER TABLE schedule DROP CONSTRAINT IF EXISTS schedule_status_check;
ALTER TABLE schedule
    ALTER COLUMN status SET DEFAULT 'PENDING_APPROVAL',
    ADD CONSTRAINT schedule_status_check
        CHECK (status IN ('PENDING_APPROVAL','APPROVED','FIRED','CANCELLED'));

CREATE INDEX IF NOT EXISTS idx_schedule_template ON schedule (reminder_template_id);
