-- ===================================================================
-- V019: Make delegation.ends_at nullable (supports standing delegates)
-- Bug fix #6: A workspace delegate is usually standing (no expiry).
-- Making ends_at optional removes the mandatory ≤30-day window.
-- ===================================================================

ALTER TABLE delegation ALTER COLUMN ends_at DROP NOT NULL;
