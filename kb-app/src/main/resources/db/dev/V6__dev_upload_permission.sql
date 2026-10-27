-- =====================================================================
-- V6: 开发种子上传权限
--
-- 这是开发环境辅助迁移，不属于生产权限初始化。它只在 V3 的明确
-- 技术/财务种子存在时生效；生产环境应和 V3 一起从 Flyway location 移除。
-- 单独迁移是为了不修改已经执行过的 V3，避免 Flyway checksum 失效。
-- =====================================================================

INSERT INTO kb_space_acl (space_id, principal_type, principal_id, permission)
SELECT s.id, 3, d.id, 'edit'
FROM kb_space s
         JOIN sys_dept d ON d.id = 2 AND d.name = '研发中心'
WHERE s.id = 1
  AND s.code = 'TECH' ON CONFLICT (space_id, principal_type, principal_id, permission) DO NOTHING;

INSERT INTO kb_space_acl (space_id, principal_type, principal_id, permission)
SELECT s.id, 3, d.id, 'edit'
FROM kb_space s
         JOIN sys_dept d ON d.id = 3 AND d.name = '财务部'
WHERE s.id = 2
  AND s.code = 'FINANCE' ON CONFLICT (space_id, principal_type, principal_id, permission) DO NOTHING;
