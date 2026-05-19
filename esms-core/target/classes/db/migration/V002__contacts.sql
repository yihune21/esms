-- ===================================================================
-- V002: Contact & Address Book tables
-- Reference: LLD §4.3
-- ===================================================================

-- ── contact_upload (batch import lifecycle) ──────────────────────
CREATE TABLE contact_upload (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    original_name   VARCHAR(255) NOT NULL,
    file_size       BIGINT       NOT NULL,
    content_type    VARCHAR(50)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','MAPPED','COMMITTED','FAILED')),
    detected_cols   JSONB,
    mapping         JSONB,
    row_count       INTEGER,
    imported_count  INTEGER,
    duplicate_count INTEGER,
    error_count     INTEGER,
    errors          JSONB,
    uploaded_by     UUID         NOT NULL REFERENCES app_user(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_upload_ws ON contact_upload (workspace_id);

-- ── contact ──────────────────────────────────────────────────────
CREATE TABLE contact (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    name            VARCHAR(120) NOT NULL,
    phone_e164      VARCHAR(15)  NOT NULL,
    branch          VARCHAR(100),
    extra           JSONB        DEFAULT '{}',
    upload_id       UUID         REFERENCES contact_upload(id),
    opt_out         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, phone_e164)
);

CREATE INDEX idx_contact_ws         ON contact (workspace_id);
CREATE INDEX idx_contact_ws_branch  ON contact (workspace_id, branch);
CREATE INDEX idx_contact_phone      ON contact (phone_e164);
CREATE INDEX idx_contact_name       ON contact USING gin (name gin_trgm_ops);

-- Trigram index requires extension
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── contact_group ────────────────────────────────────────────────
CREATE TABLE contact_group (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    name            VARCHAR(120) NOT NULL,
    description     VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);

CREATE INDEX idx_cg_ws ON contact_group (workspace_id);

-- ── contact_group_member (M2M) ──────────────────────────────────
CREATE TABLE contact_group_member (
    group_id        UUID NOT NULL REFERENCES contact_group(id) ON DELETE CASCADE,
    contact_id      UUID NOT NULL REFERENCES contact(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, contact_id)
);

-- ── policy_record (Underwriting) ─────────────────────────────────
CREATE TABLE policy_record (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    policy_number   VARCHAR(50)  NOT NULL,
    contact_id      UUID         NOT NULL REFERENCES contact(id),
    expiry_date     DATE         NOT NULL,
    renewed         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, policy_number)
);

CREATE INDEX idx_policy_ws      ON policy_record (workspace_id);
CREATE INDEX idx_policy_expiry  ON policy_record (workspace_id, expiry_date) WHERE renewed = FALSE;
