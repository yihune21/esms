-- ===================================================================
-- V009: Soft-delete fields, workspace permissions, and campaign upload
-- ===================================================================

-- ── Add status to contact (soft-delete) ──────────────────────────
ALTER TABLE contact ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE contact ADD CONSTRAINT chk_contact_status CHECK (status IN ('ACTIVE','INACTIVE'));

-- ── Add status to contact_group (soft-delete) ────────────────────
ALTER TABLE contact_group ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE contact_group ADD CONSTRAINT chk_contact_group_status CHECK (status IN ('ACTIVE','INACTIVE'));

-- ── Add division to workspace ────────────────────────────────────
ALTER TABLE workspace ADD COLUMN IF NOT EXISTS division VARCHAR(120);

-- ── Add fields (dynamic column definitions) to contact_group ─────
ALTER TABLE contact_group ADD COLUMN IF NOT EXISTS fields JSONB DEFAULT '[]';

-- ── Workspace permissions (feature flags per workspace) ──────────
CREATE TABLE IF NOT EXISTS workspace_permission (
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    permission_code VARCHAR(80) NOT NULL,
    PRIMARY KEY (workspace_id, permission_code)
);

-- ── Add upload_id to campaign for inline Excel recipients ────────
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS upload_id UUID REFERENCES contact_upload(id);
