-- 功能权限由代码目录定义；角色 code 只用于定位迁移目标。
CREATE TABLE sys_role_permission
(
    role_id    BIGINT NOT NULL REFERENCES sys_role (id) ON DELETE CASCADE,
    permission TEXT   NOT NULL,
    PRIMARY KEY (role_id, permission)
);

INSERT INTO sys_role_permission (role_id, permission)
SELECT r.id, p.permission
FROM sys_role r
         CROSS JOIN (VALUES ('identity:user:read'),
                            ('identity:user:write'),
                            ('identity:user:password'),
                            ('identity:clearance:write'),
                            ('identity:role:read'),
                            ('identity:role:assign'),
                            ('identity:role:write'),
                            ('identity:dept:read'),
                            ('identity:dept:write'),
                            ('identity:external:write'),
                            ('identity:audit:read'),
                            ('parse:deadletter:read'),
                            ('parse:deadletter:replay'),
                            ('space:create'),
                            ('space:manage-all'),
                            ('space:grant-manage')) AS p(permission)
WHERE r.code = 'ROLE_ADMIN';
