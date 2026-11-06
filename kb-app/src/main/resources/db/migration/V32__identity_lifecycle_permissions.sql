-- 仅升级时一次性授予已有管理员；启动引导不得补回之后撤销的权限。
INSERT INTO sys_role_permission (role_id, permission)
SELECT r.id, p.permission
FROM sys_role r
CROSS JOIN (VALUES ('identity:lifecycle:read'),
                   ('identity:lifecycle:manage'),
                   ('identity:lifecycle:restore'),
                   ('identity:emergency:manage')) AS p(permission)
WHERE r.code = 'ROLE_ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
