-- ===================================================================
-- V020: Make template_id in schedule (reminder) table nullable
-- Bug fix: The frontend lets users type a free-form custom message
-- without forcing template selection, so template_id must be optional.
-- ===================================================================

ALTER TABLE schedule ALTER COLUMN template_id DROP NOT NULL;
