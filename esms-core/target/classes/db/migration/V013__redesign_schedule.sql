-- ===================================================================
-- V013: Redesign Schedule for rules-based reminders
-- ===================================================================

ALTER TABLE schedule
DROP COLUMN IF EXISTS policy_id,
DROP COLUMN IF EXISTS due_date;

ALTER TABLE schedule
ADD COLUMN IF NOT EXISTS name VARCHAR(255),
ADD COLUMN IF NOT EXISTS recipient_group_id UUID REFERENCES contact_group(id),
ADD COLUMN IF NOT EXISTS upload_id UUID,
ADD COLUMN IF NOT EXISTS custom_body TEXT;

UPDATE schedule SET status = 'INACTIVE' WHERE status IN ('PENDING', 'FIRED', 'CANCELLED');
