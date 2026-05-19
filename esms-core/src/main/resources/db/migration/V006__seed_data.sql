-- ===================================================================
-- V006: Seed Data — permissions, roles, workspaces, transitions, carriers
-- Reference: LLD §4.2, §6.3, §7.1, §11.1
-- ===================================================================

-- ── Permission Catalogue ─────────────────────────────────────────
INSERT INTO permission (id, code, category, description) VALUES
-- Workspace
(uuid_generate_v4(), 'WORKSPACE_VIEW',       'WORKSPACE', 'View workspace details'),
(uuid_generate_v4(), 'WORKSPACE_CREATE',     'WORKSPACE', 'Create new workspaces'),
(uuid_generate_v4(), 'WORKSPACE_UPDATE',     'WORKSPACE', 'Update workspace settings'),
(uuid_generate_v4(), 'WORKSPACE_MEMBER_ADD', 'WORKSPACE', 'Add members to workspace'),
(uuid_generate_v4(), 'WORKSPACE_MEMBER_REMOVE','WORKSPACE','Remove members from workspace'),
-- Contact
(uuid_generate_v4(), 'CONTACT_VIEW',     'CONTACT', 'View contacts'),
(uuid_generate_v4(), 'CONTACT_CREATE',   'CONTACT', 'Create individual contacts'),
(uuid_generate_v4(), 'CONTACT_UPDATE',   'CONTACT', 'Update contacts'),
(uuid_generate_v4(), 'CONTACT_DELETE',   'CONTACT', 'Delete contacts'),
(uuid_generate_v4(), 'CONTACT_UPLOAD',   'CONTACT', 'Bulk upload contacts'),
-- Template
(uuid_generate_v4(), 'TEMPLATE_VIEW',    'TEMPLATE', 'View templates'),
(uuid_generate_v4(), 'TEMPLATE_CREATE',  'TEMPLATE', 'Create/edit templates'),
(uuid_generate_v4(), 'TEMPLATE_APPROVE', 'TEMPLATE', 'Approve templates'),
-- Campaign
(uuid_generate_v4(), 'CAMPAIGN_VIEW',    'CAMPAIGN', 'View campaigns'),
(uuid_generate_v4(), 'CAMPAIGN_DRAFT',   'CAMPAIGN', 'Create/edit campaign drafts'),
(uuid_generate_v4(), 'CAMPAIGN_SUBMIT',  'CAMPAIGN', 'Submit campaigns for approval'),
(uuid_generate_v4(), 'CAMPAIGN_APPROVE', 'CAMPAIGN', 'Approve campaigns (Tier-1)'),
(uuid_generate_v4(), 'CAMPAIGN_APPROVE_CEO','CAMPAIGN','Approve campaigns (CEO/Tier-2)'),
(uuid_generate_v4(), 'CAMPAIGN_CANCEL',  'CAMPAIGN', 'Cancel campaigns'),
-- Report
(uuid_generate_v4(), 'REPORT_VIEW',      'REPORT', 'View delivery reports'),
(uuid_generate_v4(), 'REPORT_EXPORT',    'REPORT', 'Export reports to XLSX/CSV'),
-- Audit
(uuid_generate_v4(), 'AUDIT_VIEW',       'AUDIT', 'View audit log'),
-- Admin
(uuid_generate_v4(), 'ADMIN_RETENTION',  'ADMIN', 'Run retention sweep'),
(uuid_generate_v4(), 'ADMIN_ROLE_MANAGE','ADMIN', 'Create/edit/retire custom roles'),
(uuid_generate_v4(), 'ADMIN_DELEGATE',   'ADMIN', 'Create delegations');

-- ── Roles ────────────────────────────────────────────────────────
INSERT INTO role (id, code, name, scope, immutable) VALUES
('00000000-0000-0000-0000-000000000001', 'SUPER_ADMIN', 'Super Admin',      'PLATFORM',  TRUE),
('00000000-0000-0000-0000-000000000002', 'DEPT_HEAD',   'Department Head',  'WORKSPACE', FALSE),
('00000000-0000-0000-0000-000000000003', 'OPERATOR',    'Operator',         'WORKSPACE', FALSE),
('00000000-0000-0000-0000-000000000004', 'VIEWER',      'Viewer',           'WORKSPACE', FALSE);

-- Super Admin permissions (all)
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000001', id FROM permission;

-- Dept Head permissions
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000002', id FROM permission
WHERE code IN (
    'WORKSPACE_VIEW','WORKSPACE_MEMBER_ADD','WORKSPACE_MEMBER_REMOVE',
    'CONTACT_VIEW','CONTACT_CREATE','CONTACT_UPDATE','CONTACT_DELETE','CONTACT_UPLOAD',
    'TEMPLATE_VIEW','TEMPLATE_CREATE','TEMPLATE_APPROVE',
    'CAMPAIGN_VIEW','CAMPAIGN_DRAFT','CAMPAIGN_SUBMIT','CAMPAIGN_APPROVE','CAMPAIGN_CANCEL',
    'REPORT_VIEW','REPORT_EXPORT','AUDIT_VIEW','ADMIN_DELEGATE'
);

-- Operator permissions
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000003', id FROM permission
WHERE code IN (
    'WORKSPACE_VIEW',
    'CONTACT_VIEW','CONTACT_CREATE','CONTACT_UPDATE','CONTACT_UPLOAD',
    'TEMPLATE_VIEW','TEMPLATE_CREATE',
    'CAMPAIGN_VIEW','CAMPAIGN_DRAFT','CAMPAIGN_SUBMIT',
    'REPORT_VIEW','REPORT_EXPORT'
);

-- Viewer permissions
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000004', id FROM permission
WHERE code IN ('WORKSPACE_VIEW','CONTACT_VIEW','TEMPLATE_VIEW','CAMPAIGN_VIEW','REPORT_VIEW');

-- ── Default Workspaces ───────────────────────────────────────────
INSERT INTO workspace (id, code, name, kind) VALUES
('10000000-0000-0000-0000-000000000001', 'underwriting', 'Underwriting', 'UNDERWRITING'),
('10000000-0000-0000-0000-000000000002', 'finance',      'Finance',      'FINANCE'),
('10000000-0000-0000-0000-000000000003', 'claims',       'Claims',       'CLAIMS'),
('10000000-0000-0000-0000-000000000004', 'marketing',    'Marketing',    'MARKETING'),
('10000000-0000-0000-0000-000000000005', 'it-admin',     'IT Admin',     'GENERIC');

-- ── Allowed Transitions per workspace kind (LLD §7.1) ────────────
-- Standard 1-tier: UW, Claims, Marketing, Generic
INSERT INTO allowed_transition (workspace_kind, from_state, to_state) VALUES
('UNDERWRITING',  'DRAFT',            'PENDING_APPROVAL'),
('UNDERWRITING',  'PENDING_APPROVAL', 'APPROVED'),
('UNDERWRITING',  'PENDING_APPROVAL', 'DRAFT'),
('CLAIMS',        'DRAFT',            'PENDING_APPROVAL'),
('CLAIMS',        'PENDING_APPROVAL', 'APPROVED'),
('CLAIMS',        'PENDING_APPROVAL', 'DRAFT'),
('MARKETING',     'DRAFT',            'PENDING_APPROVAL'),
('MARKETING',     'PENDING_APPROVAL', 'APPROVED'),
('MARKETING',     'PENDING_APPROVAL', 'DRAFT'),
('GENERIC',       'DRAFT',            'PENDING_APPROVAL'),
('GENERIC',       'PENDING_APPROVAL', 'APPROVED'),
('GENERIC',       'PENDING_APPROVAL', 'DRAFT'),
-- Finance 2-tier
('FINANCE', 'DRAFT',         'PENDING_HEAD'),
('FINANCE', 'PENDING_HEAD',  'PENDING_CEO'),
('FINANCE', 'PENDING_HEAD',  'DRAFT'),
('FINANCE', 'PENDING_CEO',   'APPROVED'),
('FINANCE', 'PENDING_CEO',   'DRAFT'),
-- Universal
('UNDERWRITING','APPROVED','QUEUED'), ('CLAIMS','APPROVED','QUEUED'),
('MARKETING','APPROVED','QUEUED'),    ('FINANCE','APPROVED','QUEUED'),
('GENERIC','APPROVED','QUEUED'),
('UNDERWRITING','QUEUED','COMPLETED'),('CLAIMS','QUEUED','COMPLETED'),
('MARKETING','QUEUED','COMPLETED'),   ('FINANCE','QUEUED','COMPLETED'),
('GENERIC','QUEUED','COMPLETED');

-- ── Carrier Prefixes (Ethiopian mobile) ──────────────────────────
INSERT INTO carrier_prefix (prefix, carrier) VALUES
('+25191', 'ETHIO_TELECOM'), ('+25192', 'ETHIO_TELECOM'),
('+25193', 'ETHIO_TELECOM'), ('+25194', 'ETHIO_TELECOM'),
('+25195', 'ETHIO_TELECOM'), ('+25196', 'ETHIO_TELECOM'),
('+25197', 'ETHIO_TELECOM'),
('+25174', 'SAFARICOM'),     ('+25175', 'SAFARICOM');
