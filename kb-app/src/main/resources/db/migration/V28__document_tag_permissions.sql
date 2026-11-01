-- 仅首次迁移绑定当前管理员；后续引导不恢复已撤销权限。
INSERT INTO sys_role_permission (role_id, permission)
SELECT r.id, p.permission FROM sys_role r
CROSS JOIN (VALUES ('document:tag:read'), ('document:tag:write')) AS p(permission)
WHERE r.code = 'ROLE_ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
