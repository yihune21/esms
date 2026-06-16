-- ===================================================================
-- V014: System Settings
-- ===================================================================

CREATE TABLE IF NOT EXISTS system_setting (
    key VARCHAR(100) PRIMARY KEY,
    value VARCHAR(255) NOT NULL,
    description TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO system_setting (key, value, description) VALUES
('ETHIO_TELECOM_RATE_LIMIT', '100', 'Rate limit for Ethio Telecom'),
('SAFARICOM_RATE_LIMIT', '100', 'Rate limit for Safaricom'),
('RETRY_ATTEMPTS', '3', 'Number of retry attempts'),
('SESSION_TIMEOUT_MINUTES', '30', 'Session timeout in minutes'),
('LOG_RETENTION_DAYS', '90', 'Log retention period in days')
ON CONFLICT (key) DO NOTHING;
