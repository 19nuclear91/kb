-- 仅迁移时为既有管理员绑定；后续启动不恢复主动撤销的诊断资格。
INSERT INTO sys_role_permission (role_id, permission)
SELECT id, 'authorization:diagnose'
FROM sys_role
WHERE code = 'ROLE_ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
