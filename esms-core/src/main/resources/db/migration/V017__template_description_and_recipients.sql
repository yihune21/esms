-- ===================================================================
-- V017: Template — description, recipient group, inline recipients
--
-- Templates can now carry:
--   1. An optional human-readable description.
--   2. An optional link to a contact_group (reuses an existing list).
--   3. An inline list of recipients (template_recipient) — each row
--      has a mandatory phone number, so the template itself becomes
--      self-contained for small, fixed recipient sets.
--
-- When a campaign is created from a template, the sender resolves
-- recipients by merging:
--   a) the linked contact_group members  (if recipient_group_id set)
--   b) the inline template_recipient rows (if any)
-- ===================================================================

-- ── 1. Add description & recipient_group_id to template ──────────
ALTER TABLE template
    ADD COLUMN IF NOT EXISTS description        TEXT,
    ADD COLUMN IF NOT EXISTS recipient_group_id UUID NOT NULL REFERENCES contact_group(id);

CREATE INDEX IF NOT EXISTS idx_template_group ON template (recipient_group_id);

-- ── 2. Inline recipients attached to a template ───────────────────
CREATE TABLE IF NOT EXISTS template_recipient (
    id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    template_id  UUID        NOT NULL REFERENCES template(id) ON DELETE CASCADE,
    phone_e164   VARCHAR(20) NOT NULL,
    name         VARCHAR(120),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (template_id, phone_e164)
);

CREATE INDEX IF NOT EXISTS idx_tmpl_recipient_template ON template_recipient (template_id);
