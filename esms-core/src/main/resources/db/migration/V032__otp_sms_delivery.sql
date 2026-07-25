-- ─── Login OTPs are delivered as real SMS ──────────────────────────────────
-- The OTP was only ever written to the application log and never sent. Sending
-- it means putting it through the same outbox -> RabbitMQ -> sender pipeline
-- campaigns use, which needs a `message` row per send.
--
-- An OTP is platform-level authentication traffic, not workspace traffic:
--   * a SUPER_ADMIN holds no workspace membership, so there is genuinely no
--     workspace to attribute their login OTP to; and
--   * attributing OTPs to the user's workspace would inflate that workspace's
--     delivery-report totals with traffic no campaign produced.
-- So OTP messages carry a NULL workspace_id. Every other source must still
-- carry its tenant boundary, which the CHECK below keeps enforcing.
ALTER TABLE message ALTER COLUMN workspace_id DROP NOT NULL;

ALTER TABLE message ADD CONSTRAINT chk_message_workspace_required
    CHECK (workspace_id IS NOT NULL OR source = 'OTP');

-- StatusEventConsumer copies the message's workspace onto each status event,
-- so DLRs for a workspace-less OTP would otherwise violate NOT NULL here.
ALTER TABLE message_status_event ALTER COLUMN workspace_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_message_otp ON message (created_at DESC)
    WHERE source = 'OTP';
