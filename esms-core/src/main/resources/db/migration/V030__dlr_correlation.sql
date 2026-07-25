-- ─── Delivery receipts can finally be matched to a message ─────────────────
-- An SMPP delivery receipt quotes only the SMSC's own message id, never ours.
-- The sender published those receipts with message_id unset, so
-- StatusEventConsumer called messageRepo.findById(null) and every receipt blew
-- up and was discarded — no message could ever reach DELIVERED, and every
-- delivery-rate figure in the app read 0%.
--
-- This table is the missing map. One row per SMSC id is recorded at submit
-- time; a receipt then resolves back to the message that produced it.
-- A multi-part send yields one id (and one receipt) PER SEGMENT, so a message
-- legitimately owns several rows here — hence a separate table rather than
-- another column on message.
CREATE TABLE message_carrier_ref (
    carrier_msg_id VARCHAR(100) PRIMARY KEY,
    message_id     UUID         NOT NULL REFERENCES message(id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_mcr_message ON message_carrier_ref (message_id);

-- Backfill the ids already stored on message rows so receipts still in flight
-- for previously-sent messages resolve too.
INSERT INTO message_carrier_ref (carrier_msg_id, message_id)
SELECT DISTINCT ON (carrier_msg_id) carrier_msg_id, id
FROM message
WHERE carrier_msg_id IS NOT NULL
ORDER BY carrier_msg_id, created_at DESC
ON CONFLICT (carrier_msg_id) DO NOTHING;

-- Supports the legacy fallback lookup for anything the backfill missed.
CREATE INDEX IF NOT EXISTS idx_message_carrier_msg_id ON message (carrier_msg_id)
    WHERE carrier_msg_id IS NOT NULL;
