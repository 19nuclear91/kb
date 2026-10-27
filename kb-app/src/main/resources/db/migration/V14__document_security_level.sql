-- 独立于正文版本，只有实际密级变更才推进此版本。
ALTER TABLE kb_document
    ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_document_security_version_nonnegative CHECK (security_version >= 0);

-- 一次性升级授权；启动引导不得恢复以后人工撤销的权限。
INSERT INTO sys_role_permission (role_id, permission)
SELECT r.id, 'document:security-level:write'
FROM sys_role r
WHERE r.code = 'ROLE_ADMIN' ON CONFLICT DO NOTHING;
