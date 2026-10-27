INSERT INTO sys_role_permission (role_id, permission)
SELECT r.id, p.permission
FROM sys_role r
         CROSS JOIN (VALUES ('embedding:read'), ('embedding:rebuild')) AS p(permission)
WHERE r.code = 'ROLE_ADMIN' ON CONFLICT (role_id, permission) DO NOTHING;
