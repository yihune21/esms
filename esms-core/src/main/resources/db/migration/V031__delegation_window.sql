-- ─── Delegation window: align the DB with what the app actually allows ─────
-- V001 capped a delegation at 30 days via an inline (unnamed) CHECK.
-- DelegationController allows up to 365 days, and V019 made ends_at nullable
-- so a standing delegation with no end date is valid too. The 30-day cap was
-- never revisited, so any window of 31–365 days passed application validation
-- and then died on a constraint violation the GlobalExceptionHandler reports
-- as "A record with that identifier already exists" — an error message with no
-- relation to the actual cause.
--
-- The constraint is dropped by definition rather than by name because V001
-- declared it inline, leaving Postgres to auto-name it (delegation_check1 on a
-- clean install, but not guaranteed across environments).
DO $$
DECLARE
    con_name TEXT;
BEGIN
    FOR con_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'delegation'::regclass
          AND contype  = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%30 days%'
    LOOP
        EXECUTE format('ALTER TABLE delegation DROP CONSTRAINT %I', con_name);
    END LOOP;
END $$;

-- Re-stated at the limit the application enforces, and named this time so it
-- can be found and changed directly. A NULL ends_at (standing delegation)
-- yields NULL here, which Postgres treats as satisfied.
ALTER TABLE delegation
    ADD CONSTRAINT chk_delegation_max_window
        CHECK (ends_at IS NULL OR ends_at <= starts_at + INTERVAL '365 days');
