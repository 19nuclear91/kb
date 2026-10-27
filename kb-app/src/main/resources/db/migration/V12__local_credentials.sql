-- =====================================================================
-- V12: 本地凭据列
--
-- 为本地用户名密码登录补充凭据存储。password_hash 可空：将来纯外部
-- 身份（钉钉）账号可以没有本地密码，登录对空哈希直接拒绝。
-- 既有用户一律不回填——开发种子用户保持无密码，dev-login 不受影响；
-- must_change_password 默认 FALSE，只有新建/重置路径才显式置 TRUE。
-- =====================================================================

ALTER TABLE sys_user
    ADD COLUMN password_hash TEXT,
    ADD COLUMN password_updated_at  TIMESTAMPTZ,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
