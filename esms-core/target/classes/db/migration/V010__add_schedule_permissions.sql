INSERT INTO permission (id, code, category, description) VALUES
(uuid_generate_v4(), 'SCHEDULE_VIEW',   'SCHEDULE', 'View schedules and reminders'),
(uuid_generate_v4(), 'SCHEDULE_MANAGE', 'SCHEDULE', 'Create, edit, and cancel schedules');

INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000001', id FROM permission
WHERE code IN ('SCHEDULE_VIEW', 'SCHEDULE_MANAGE');

INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000002', id FROM permission
WHERE code IN ('SCHEDULE_VIEW', 'SCHEDULE_MANAGE');
