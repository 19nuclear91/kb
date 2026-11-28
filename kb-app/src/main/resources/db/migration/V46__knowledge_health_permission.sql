-- 用途：为知识健康雷达登记既有管理员的一次性功能资格；不修改空间或文档授权。
-- 仅向本次迁移时已经存在的 ROLE_ADMIN 绑定读取权限，其他角色须显式分配。
-- 后续启动不重放此初始化，已撤销绑定不得自动恢复；旧 app 须在本迁移前排空。
INSERT INTO sys_role_permission (role_id, permission)
SELECT id, 'knowledge:health:read'
FROM sys_role
WHERE code = 'ROLE_ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
