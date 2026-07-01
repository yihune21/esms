CREATE TABLE message (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    campaign_id     UUID         REFERENCES campaign(id),
    contact_id      UUID         REFERENCES contact(id),
    to_number       VARCHAR(15)  NOT NULL,
    body            TEXT         NOT NULL,
    encoding        VARCHAR(10)  NOT NULL DEFAULT 'GSM7',
    segment_count   SMALLINT     NOT NULL DEFAULT 1,
    resolved_carrier VARCHAR(30),
    source          VARCHAR(20)  NOT NULL DEFAULT 'CAMPAIGN'
                        CHECK (source IN ('CAMPAIGN','AUTO_SCHEDULER','OTP','SYSTEM')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','QUEUED','SENT','DELIVERED','FAILED','EXPIRED')),
    carrier_msg_id  VARCHAR(100),
    error_code      VARCHAR(50),
    attempts        SMALLINT     NOT NULL DEFAULT 0,
    sent_at         TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_message_ws         ON message (workspace_id);
CREATE INDEX idx_message_campaign   ON message (campaign_id);
CREATE INDEX idx_message_status     ON message (workspace_id, status);
CREATE INDEX idx_message_created    ON message (workspace_id, created_at);

CREATE TABLE message_status_event (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id      UUID         NOT NULL REFERENCES message(id),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    status          VARCHAR(20)  NOT NULL,
    carrier         VARCHAR(30),
    carrier_msg_id  VARCHAR(100),
    error_code      VARCHAR(50),
    raw_payload     JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_mse_message ON message_status_event (message_id);

CREATE TABLE outbox_event (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_type  VARCHAR(30)  NOT NULL DEFAULT 'message',
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(30)  NOT NULL DEFAULT 'SendCommand',
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at)
    WHERE published_at IS NULL;
