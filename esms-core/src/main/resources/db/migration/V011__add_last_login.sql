-- ===================================================================
-- V011: Add last login at to app_user
-- ===================================================================

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;
