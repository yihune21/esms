-- ===================================================================
-- V007: Approval Transition Trigger
-- Reference: LLD §7.2
-- ===================================================================

CREATE OR REPLACE FUNCTION check_approval_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM allowed_transition
        WHERE workspace_kind = (
            SELECT kind FROM workspace WHERE id = NEW.workspace_id
        )
        AND from_state = NEW.from_state
        AND to_state   = NEW.to_state
    ) THEN
        RAISE EXCEPTION 'Illegal approval transition % → % for workspace %',
            NEW.from_state, NEW.to_state, NEW.workspace_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_check_approval_transition
BEFORE INSERT ON approval
FOR EACH ROW EXECUTE FUNCTION check_approval_transition();
