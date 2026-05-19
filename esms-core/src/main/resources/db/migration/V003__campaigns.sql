-- ===================================================================
-- V003: Campaign, Template, Approval, Schedule tables
-- Reference: LLD §4.4, §4.5, §7, §8
-- ===================================================================

-- ── template ─────────────────────────────────────────────────────
CREATE TABLE template (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    name            VARCHAR(120) NOT NULL,
    body            TEXT         NOT NULL,
    encoding        VARCHAR(10)  NOT NULL DEFAULT 'GSM7'
                        CHECK (encoding IN ('GSM7','UCS2')),
    variables       JSONB        DEFAULT '[]',
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','APPROVED','RETIRED')),
    approved_by     UUID         REFERENCES app_user(id),
    approved_at     TIMESTAMPTZ,
    created_by      UUID         NOT NULL REFERENCES app_user(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);

CREATE INDEX idx_template_ws ON template (workspace_id);

-- ── campaign ─────────────────────────────────────────────────────
CREATE TABLE campaign (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    name            VARCHAR(120) NOT NULL,
    kind            VARCHAR(20)  NOT NULL
                        CHECK (kind IN ('SINGLE','BULK','SCHEDULED','AUTO')),
    status          VARCHAR(30)  NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN (
                            'DRAFT','PENDING_APPROVAL','PENDING_HEAD','PENDING_CEO',
                            'APPROVED','QUEUED','COMPLETED','CANCELLED'
                        )),
    template_id     UUID         REFERENCES template(id),
    custom_body     TEXT,
    recipient_group_id UUID      REFERENCES contact_group(id),
    recipient_count INTEGER      DEFAULT 0,
    scheduled_at    TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_by      UUID         NOT NULL REFERENCES app_user(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_campaign_ws        ON campaign (workspace_id);
CREATE INDEX idx_campaign_ws_status ON campaign (workspace_id, status);
CREATE INDEX idx_campaign_schedule  ON campaign (scheduled_at) WHERE status = 'APPROVED';

-- ── allowed_transitions (reference table for trigger) ────────────
CREATE TABLE allowed_transition (
    id              SERIAL PRIMARY KEY,
    workspace_kind  VARCHAR(30) NOT NULL,
    from_state      VARCHAR(30) NOT NULL,
    to_state        VARCHAR(30) NOT NULL,
    UNIQUE (workspace_kind, from_state, to_state)
);

-- ── approval ─────────────────────────────────────────────────────
CREATE TABLE approval (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    campaign_id     UUID         NOT NULL REFERENCES campaign(id),
    from_state      VARCHAR(30)  NOT NULL,
    to_state        VARCHAR(30)  NOT NULL,
    actor_user_id   UUID         NOT NULL REFERENCES app_user(id),
    delegate_of     UUID         REFERENCES app_user(id),
    note            VARCHAR(500),
    snapshot_hash   VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_approval_campaign ON approval (campaign_id);
CREATE INDEX idx_approval_ws       ON approval (workspace_id);

-- ── schedule (Underwriting T-30/T-10) ────────────────────────────
CREATE TABLE schedule (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    policy_id       UUID         NOT NULL REFERENCES policy_record(id),
    kind            VARCHAR(20)  NOT NULL
                        CHECK (kind IN ('T_MINUS_30','T_MINUS_10','CUSTOM')),
    due_date        DATE         NOT NULL,
    template_id     UUID         NOT NULL REFERENCES template(id),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','FIRED','CANCELLED')),
    fired_at        TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_schedule_due ON schedule (workspace_id, due_date, status)
    WHERE status = 'PENDING';
