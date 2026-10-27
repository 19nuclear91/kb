-- =====================================================================
-- V11: 知识空间乐观锁版本列
--
-- 空间元数据写操作与既有身份/部门管理接口共用同一套乐观锁约定
-- （参照 V8 对 sys_dept / sys_user 的处理），使调用方只需遵循一套
-- 版本冲突规则，不必按实体记两套。
--
-- 空间 ACL 的授予与撤销是 insert / delete，且由
-- UNIQUE (space_id, principal_type, principal_id, permission) 兜底，
-- 天然幂等，因此不需要版本列。
-- =====================================================================

ALTER TABLE kb_space
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE kb_space
    ADD CONSTRAINT ck_kb_space_version_nonnegative CHECK (version >= 0);

COMMENT
ON COLUMN kb_space.version IS '乐观锁版本；空间元数据更新必须携带读取到的版本，过期版本一律拒绝';
