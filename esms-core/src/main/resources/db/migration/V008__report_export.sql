-- ===================================================================
-- V008: Report Export table
-- Reference: LLD §6.7
-- ===================================================================

CREATE TABLE report_export (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id    UUID         NOT NULL REFERENCES workspace(id),
    requested_by    UUID         REFERENCES app_user(id),
    format          VARCHAR(10)  NOT NULL,
    filter_json     TEXT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'RUNNING'
                        CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    file_path       VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_report_export_ws ON report_export (workspace_id);
