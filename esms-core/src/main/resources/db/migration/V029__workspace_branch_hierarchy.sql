-- Branch/division hierarchy. A "branch workspace" (umbrella) owns branches and
-- holds no data of its own; each branch behaves like a standalone workspace
-- (own members, campaigns, contacts) but inherits the umbrella's feature
-- permissions. A branch sets parent_workspace_id; an umbrella sets
-- is_branch_parent = TRUE and must always have ≥1 branch.
ALTER TABLE workspace ADD COLUMN parent_workspace_id UUID REFERENCES workspace(id);
ALTER TABLE workspace ADD COLUMN is_branch_parent BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_workspace_parent ON workspace(parent_workspace_id);

-- Convert Underwriting into a branch workspace WITHOUT moving any of its data:
-- the existing Underwriting row (with all its members, campaigns, contacts,
-- templates, reminders) becomes its first branch, and a fresh umbrella named
-- "Underwriting" is created above it. Only the workspace_permission rows move
-- up to the umbrella, which becomes the single source of truth branches inherit.
--
-- Order matters: (1) free the "underwriting" code by renaming the existing row
-- first — without yet pointing it at a parent — so we hit neither the unique
-- code constraint nor the parent FK; (2) create the umbrella so the FK target
-- exists; (3) only then point the branch at it.
DO $$
DECLARE
    existing_id   UUID := '10000000-0000-0000-0000-000000000001';
    umbrella_id   UUID := '20000000-0000-0000-0000-000000000001';
BEGIN
    IF EXISTS (SELECT 1 FROM workspace WHERE id = existing_id) THEN
        UPDATE workspace
           SET name = 'Underwriting — Main Branch',
               code = 'underwriting-main',
               is_branch_parent = FALSE
         WHERE id = existing_id;

        INSERT INTO workspace (id, code, name, kind, status, is_branch_parent, created_at, updated_at)
        VALUES (umbrella_id, 'underwriting', 'Underwriting', 'UNDERWRITING', 'ACTIVE', TRUE, now(), now());

        UPDATE workspace SET parent_workspace_id = umbrella_id WHERE id = existing_id;

        UPDATE workspace_permission SET workspace_id = umbrella_id WHERE workspace_id = existing_id;
    END IF;
END $$;
