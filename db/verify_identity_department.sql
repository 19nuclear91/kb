-- V8 身份/部门模型离线/联机验证脚本。
-- 用法：psql -h <host> -U <user> -d kb -v ON_ERROR_STOP=1 -f db/verify_identity_department.sql

\set
ON_ERROR_STOP on
\echo '########## 身份/部门模型结构 ##########'

SELECT 'sys_dept' AS table_name, count(*) AS rows
FROM sys_dept;
SELECT 'sys_user' AS table_name, count(*) AS rows
FROM sys_user;

DO
$$
BEGIN
    IF
EXISTS (SELECT 1 FROM sys_dept WHERE code IS NULL OR NOT enabled OR version < 0) THEN
        RAISE EXCEPTION '部门回填或版本约束失败';
END IF;
    IF
EXISTS (SELECT 1 FROM sys_user WHERE version < 0) THEN
        RAISE EXCEPTION '用户版本约束失败';
END IF;
    IF
EXISTS (
        SELECT 1 FROM sys_dept child
         WHERE child.parent_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM sys_dept parent WHERE parent.id = child.parent_id)
    ) THEN
        RAISE EXCEPTION '发现孤儿部门引用';
END IF;
END $$;

-- 唯一约束与审计幂等键必须存在。
SELECT conname
FROM pg_constraint
WHERE conrelid IN ('sys_user_identity'::regclass, 'sys_identity_audit'::regclass)
  AND conname IN ('sys_user_identity_provider_subject_key', 'sys_identity_audit_operation_scope_request_id_key')
ORDER BY conname;

\echo
'########## 历史主体链 ##########'
SELECT u.id AS user_id, u.username, u.dept_id, d.path
FROM sys_user u
         LEFT JOIN sys_dept d ON d.id = u.dept_id
ORDER BY u.id;

