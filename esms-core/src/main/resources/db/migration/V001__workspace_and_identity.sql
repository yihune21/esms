-- ===================================================================
-- V001: Workspace & Identity tables
-- Reference: LLD §4.1, §4.2
-- ===================================================================

-- UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── workspace ─────────────────────────────────────────────────────
CREATE TABLE workspace (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code            VARCHAR(50)  NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    kind            VARCHAR(30)  NOT NULL
                        CHECK (kind IN ('UNDERWRITING','FINANCE','CLAIMS','MARKETING','GENERIC')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','SUSPENDED','ARCHIVED')),
    sender_mask     VARCHAR(20),
    daily_sms_limit INTEGER      DEFAULT 10000,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_workspace_kind ON workspace (kind);

-- ── app_user ──────────────────────────────────────────────────────
CREATE TABLE app_user (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ad_sam          VARCHAR(64)  UNIQUE,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    display_name    VARCHAR(120) NOT NULL,
    email           VARCHAR(255),
    mobile_enc      BYTEA,                          -- AES-256-GCM encrypted mobile
    mobile_hash     VARCHAR(64),                    -- SHA-256 hash for lookup
    password_hash   VARCHAR(255),                   -- bcrypt, used when AD is disabled
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','DISABLED','LOCKED')),
    locked_until    TIMESTAMPTZ,
    failed_logins   SMALLINT     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_ad_sam ON app_user (ad_sam);
CREATE INDEX idx_user_status ON app_user (status);

-- ── permission (seeded catalogue — never edited at runtime) ──────
CREATE TABLE permission (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code            VARCHAR(80)  NOT NULL UNIQUE,
    category        VARCHAR(40)  NOT NULL,
    description     VARCHAR(255) NOT NULL
);

-- ── role ──────────────────────────────────────────────────────────
CREATE TABLE role (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code            VARCHAR(60)  NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    scope           VARCHAR(20)  NOT NULL
                        CHECK (scope IN ('PLATFORM','WORKSPACE')),
    immutable       BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','RETIRED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── role_permission (M2M) ────────────────────────────────────────
CREATE TABLE role_permission (
    role_id         UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_id   UUID NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- ── workspace_member ─────────────────────────────────────────────
CREATE TABLE workspace_member (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id         UUID NOT NULL REFERENCES role(id),
    assigned_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    assigned_by     UUID REFERENCES app_user(id),
    UNIQUE (workspace_id, user_id)
);

CREATE INDEX idx_wm_workspace ON workspace_member (workspace_id);
CREATE INDEX idx_wm_user      ON workspace_member (user_id);

-- ── delegation ───────────────────────────────────────────────────
CREATE TABLE delegation (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    from_user_id    UUID NOT NULL REFERENCES app_user(id),
    to_user_id      UUID NOT NULL REFERENCES app_user(id),
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    reason          VARCHAR(255),
    revoked         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (ends_at <= starts_at + INTERVAL '30 days')
);

CREATE INDEX idx_delegation_ws_active ON delegation (workspace_id, to_user_id)
    WHERE revoked = FALSE;
