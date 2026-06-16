-- ===================================================================
-- V012: Update Template (rejection_reason, variables, sender)
-- ===================================================================

ALTER TABLE template
ADD COLUMN IF NOT EXISTS rejection_reason TEXT,
ADD COLUMN IF NOT EXISTS variables JSONB,
ADD COLUMN IF NOT EXISTS sender VARCHAR(50);
