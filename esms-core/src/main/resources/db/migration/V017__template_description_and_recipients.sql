ALTER TABLE template
    ADD COLUMN IF NOT EXISTS description        TEXT,
    ADD COLUMN IF NOT EXISTS recipient_group_id UUID NOT NULL REFERENCES contact_group(id);

CREATE INDEX IF NOT EXISTS idx_template_group ON template (recipient_group_id);

CREATE TABLE IF NOT EXISTS template_recipient (
    id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    template_id  UUID        NOT NULL REFERENCES template(id) ON DELETE CASCADE,
    phone_e164   VARCHAR(20) NOT NULL,
    name         VARCHAR(120),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (template_id, phone_e164)
);

CREATE INDEX IF NOT EXISTS idx_tmpl_recipient_template ON template_recipient (template_id);
