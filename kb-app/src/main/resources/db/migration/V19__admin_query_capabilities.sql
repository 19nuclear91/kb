-- 新读取资格只在迁移时初始化；运行时不按角色名称恢复撤权。
INSERT INTO sys_role_permission (role_id, permission)
SELECT r.id, p.permission
FROM sys_role r
         CROSS JOIN (VALUES ('audit:read'), ('identity:external:read')) AS p(permission)
WHERE r.code = 'ROLE_ADMIN' ON CONFLICT (role_id, permission) DO NOTHING;

CREATE INDEX idx_doc_manage_page ON kb_document (space_id, id DESC) WHERE status <> 'deleted';
CREATE INDEX idx_user_dept_page ON sys_user (dept_id, id);
CREATE INDEX idx_identity_audit_page ON sys_identity_audit (created_at DESC, id DESC);
CREATE INDEX idx_identity_audit_request_page
    ON sys_identity_audit (request_id, created_at DESC, id DESC);

-- 读取和索引复用同一纯函数，异常历史摘要不产生前缀误匹配。
CREATE FUNCTION kb_audit_login_username(state jsonb) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT AS $$
SELECT CASE
           WHEN jsonb_typeof(state -> 'username') = 'string'
               AND length(state ->> 'username') BETWEEN 1 AND 128
               THEN state ->> 'username'
           ELSE NULL
           END
           $$;

CREATE INDEX idx_identity_audit_login_page
    ON sys_identity_audit (kb_audit_login_username(after_state), created_at DESC, id DESC) WHERE operation_scope IN ('AUTH_LOGIN_SUCCESS', 'AUTH_LOGIN_FAILURE');
