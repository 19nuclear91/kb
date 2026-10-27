-- =====================================================================
-- V8: 统一身份、部门生命周期与变更审计
--
-- 历史用户/部门保留原 ID、ACL 和角色关系；新增字段通过回填获得
-- 稳定默认值。身份/部门使用软停用，不删除仍被业务数据引用的主体。
-- =====================================================================

ALTER TABLE sys_dept
    ADD COLUMN code TEXT,
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE sys_dept
SET code = 'legacy-dept-' || id::text
WHERE code IS NULL;

ALTER TABLE sys_dept
    ALTER COLUMN code SET NOT NULL;

ALTER TABLE sys_dept
    ADD CONSTRAINT uk_sys_dept_code UNIQUE (code),
    ADD CONSTRAINT ck_sys_dept_code_nonblank CHECK (length(btrim(code)) BETWEEN 1 AND 128),
    ADD CONSTRAINT ck_sys_dept_version_nonnegative CHECK (version >= 0);

CREATE INDEX idx_sys_dept_enabled_parent ON sys_dept (enabled, parent_id);

ALTER TABLE sys_user
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE sys_user
    ADD CONSTRAINT ck_sys_user_version_nonnegative CHECK (version >= 0);

CREATE INDEX idx_sys_user_enabled_dept ON sys_user (enabled, dept_id);

-- 外部身份只作为服务端查找键，不在 API 或审计中回显。
CREATE TABLE sys_user_identity
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    provider   TEXT        NOT NULL,
    subject    TEXT        NOT NULL,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_identity_provider CHECK (length(btrim(provider)) BETWEEN 1 AND 128),
    CONSTRAINT ck_user_identity_subject CHECK (length(btrim(subject)) BETWEEN 1 AND 512),
    UNIQUE (provider, subject)
);

CREATE INDEX idx_user_identity_user ON sys_user_identity (user_id, enabled);

-- append-only 身份/部门审计；请求键保证重试幂等。
CREATE TABLE sys_identity_audit
(
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    operation_scope TEXT        NOT NULL,
    request_id      TEXT        NOT NULL,
    actor_user_id   BIGINT REFERENCES sys_user (id),
    target_type     TEXT        NOT NULL,
    target_id       BIGINT,
    operation       TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    before_state    JSONB,
    after_state     JSONB,
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_identity_audit_scope CHECK (length(btrim(operation_scope)) BETWEEN 1 AND 128),
    CONSTRAINT ck_identity_audit_request CHECK (length(btrim(request_id)) BETWEEN 1 AND 256),
    CONSTRAINT ck_identity_audit_target_type CHECK (length(btrim(target_type)) BETWEEN 1 AND 64),
    CONSTRAINT ck_identity_audit_operation CHECK (length(btrim(operation)) BETWEEN 1 AND 128),
    UNIQUE (operation_scope, request_id)
);

CREATE INDEX idx_identity_audit_target_time
    ON sys_identity_audit (target_type, target_id, created_at DESC);
CREATE INDEX idx_identity_audit_actor_time
    ON sys_identity_audit (actor_user_id, created_at DESC);

