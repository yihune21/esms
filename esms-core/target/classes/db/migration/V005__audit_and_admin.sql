CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID,
    seq             BIGSERIAL    UNIQUE,
    actor_user_id   UUID,
    actor_username  VARCHAR(64),
    category        VARCHAR(40)  NOT NULL,
    severity        VARCHAR(10)  NOT NULL DEFAULT 'INFO'
                        CHECK (severity IN ('INFO','WARN','CRITICAL')),
    action          VARCHAR(80)  NOT NULL,
    entity_type     VARCHAR(40),
    entity_id       UUID,
    detail          JSONB,
    ip_address      VARCHAR(45),
    prev_hash       VARCHAR(64),
    row_hash        VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_ws    ON audit_log (workspace_id, created_at);
CREATE INDEX idx_audit_actor ON audit_log (actor_user_id, created_at);
CREATE INDEX idx_audit_cat   ON audit_log (category, created_at);
CREATE INDEX idx_audit_seq   ON audit_log (seq);

CREATE TABLE retention_marker (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    entity_type     VARCHAR(40)  NOT NULL,
    entity_id       UUID         NOT NULL,
    workspace_id    UUID,
    marked_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    marked_by       UUID         NOT NULL REFERENCES app_user(id),
    confirm_1_by    UUID         REFERENCES app_user(id),
    confirm_1_at    TIMESTAMPTZ,
    confirm_2_by    UUID         REFERENCES app_user(id),
    confirm_2_at    TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    status          VARCHAR(20)  NOT NULL DEFAULT 'MARKED'
                        CHECK (status IN ('MARKED','CONFIRMED','DELETED')),
    UNIQUE (entity_type, entity_id)
);

CREATE TABLE carrier_prefix (
    id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    prefix   VARCHAR(10) NOT NULL UNIQUE,
    carrier  VARCHAR(30) NOT NULL CHECK (carrier IN ('ETHIO_TELECOM','SAFARICOM')),
    active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_carrier_active ON carrier_prefix (prefix) WHERE active = TRUE;
