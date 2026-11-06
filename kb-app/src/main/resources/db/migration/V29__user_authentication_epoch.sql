-- 人工状态与最终启用状态分开保存；存量用户不因迁移改变可用性。
ALTER TABLE sys_user
    ADD COLUMN manual_enabled BOOLEAN,
    ADD COLUMN auth_epoch BIGINT NOT NULL DEFAULT 1;

UPDATE sys_user SET manual_enabled = enabled;

ALTER TABLE sys_user
    ALTER COLUMN manual_enabled SET NOT NULL,
    ALTER COLUMN manual_enabled SET DEFAULT TRUE,
    ADD CONSTRAINT ck_sys_user_auth_epoch_positive CHECK (auth_epoch > 0);

COMMENT ON COLUMN sys_user.manual_enabled IS '管理员设置的启停状态，不解除企业生命周期限制';
COMMENT ON COLUMN sys_user.auth_epoch IS '单调认证代次，旧代次会话永久失效';
