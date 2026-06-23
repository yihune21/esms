-- ===================================================================
-- V018: Seed CEO (Delegate / Director) role
-- Bug fix: V006 only seeded SUPER_ADMIN/DEPT_HEAD/OPERATOR/VIEWER.
-- WorkspaceController calls roleRepo.findByCode("CEO") which returned
-- empty — delegate assignment was silently dropped and delegateName /
-- delegateUserId were always null in workspace DTOs.
-- ===================================================================

-- Insert the CEO role (idempotent: skip if already present)
INSERT INTO role (id, code, name, scope, immutable)
VALUES ('00000000-0000-0000-0000-000000000005', 'CEO', 'Delegate (CEO/Director)', 'WORKSPACE', FALSE)
ON CONFLICT (id) DO NOTHING;

-- Grant the CEO role the permissions it needs:
--   WORKSPACE_VIEW      — can view the workspace
--   CAMPAIGN_VIEW       — can view campaigns awaiting their sign-off
--   CAMPAIGN_APPROVE_CEO — the "CEO tier-2 approval" gate checked by CampaignController
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000005', id
FROM permission
WHERE code IN ('WORKSPACE_VIEW', 'CAMPAIGN_VIEW', 'CAMPAIGN_APPROVE_CEO')
ON CONFLICT DO NOTHING;
