-- ===================================================================
-- V015: Remaining gap fixes
-- ===================================================================

-- ── 1. Add group_id FK to contact_upload so we know which upload belongs to which group
ALTER TABLE contact_upload
    ADD COLUMN IF NOT EXISTS group_id UUID REFERENCES contact_group(id);

CREATE INDEX IF NOT EXISTS idx_upload_group ON contact_upload (group_id);

-- ── 2. Add campaign kind CHECK constraint (aligns UI vocabulary to backend)
--    Existing rows: migrate old freetext kinds gracefully
ALTER TABLE campaign
    DROP CONSTRAINT IF EXISTS campaign_kind_check;

ALTER TABLE campaign
    ADD CONSTRAINT campaign_kind_check
        CHECK (kind IN ('INSTANT','SCHEDULED','REMINDER'));

-- ── 3. Add user_activity table for Reports → Users & Access tab
CREATE TABLE IF NOT EXISTS user_activity_log (
    id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID        REFERENCES workspace(id),
    user_id      UUID        NOT NULL REFERENCES app_user(id),
    action       VARCHAR(80) NOT NULL,  -- e.g. LOGIN, CAMPAIGN_CREATE, TEMPLATE_APPROVE …
    resource     VARCHAR(80),           -- e.g. campaign, template, workspace
    resource_id  UUID,
    ip_address   VARCHAR(45),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ual_user      ON user_activity_log (user_id);
CREATE INDEX IF NOT EXISTS idx_ual_workspace ON user_activity_log (workspace_id);
CREATE INDEX IF NOT EXISTS idx_ual_created   ON user_activity_log (created_at DESC);
