ALTER TABLE contact ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE contact ADD CONSTRAINT chk_contact_status CHECK (status IN ('ACTIVE','INACTIVE'));

ALTER TABLE contact_group ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE contact_group ADD CONSTRAINT chk_contact_group_status CHECK (status IN ('ACTIVE','INACTIVE'));

ALTER TABLE workspace ADD COLUMN IF NOT EXISTS division VARCHAR(120);

ALTER TABLE contact_group ADD COLUMN IF NOT EXISTS fields JSONB DEFAULT '[]';

CREATE TABLE IF NOT EXISTS workspace_permission (
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    permission_code VARCHAR(80) NOT NULL,
    PRIMARY KEY (workspace_id, permission_code)
);

ALTER TABLE campaign ADD COLUMN IF NOT EXISTS upload_id UUID REFERENCES contact_upload(id);
