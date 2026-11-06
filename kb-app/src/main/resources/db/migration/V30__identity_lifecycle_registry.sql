-- 同一行的 kind 同时约束受管员工和应急身份，避免跨表互斥检查的竞争。
CREATE TABLE sys_identity_lifecycle
(
    user_id BIGINT PRIMARY KEY REFERENCES sys_user (id),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('NONE', 'MANAGED', 'EMERGENCY')),
    source VARCHAR(32),
    enterprise_id VARCHAR(256),
    employee_id VARCHAR(256),
    connection_id VARCHAR(128),
    restriction VARCHAR(16) NOT NULL DEFAULT 'CLEAR'
        CHECK (restriction IN ('CLEAR', 'OFFBOARDED', 'REVIEW')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    last_event_id BIGINT,
    restored_at TIMESTAMPTZ,
    registered_by BIGINT NOT NULL REFERENCES sys_user (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((kind = 'MANAGED'
        AND source IS NOT NULL AND length(btrim(source)) > 0
        AND enterprise_id IS NOT NULL AND length(btrim(enterprise_id)) > 0
        AND employee_id IS NOT NULL AND length(btrim(employee_id)) > 0
        AND connection_id IS NOT NULL AND length(btrim(connection_id)) > 0)
        OR (kind IN ('NONE', 'EMERGENCY') AND source IS NULL AND enterprise_id IS NULL
            AND employee_id IS NULL AND connection_id IS NULL AND restriction = 'CLEAR'))
);
CREATE UNIQUE INDEX sys_identity_lifecycle_employee
    ON sys_identity_lifecycle (source, enterprise_id, employee_id) WHERE kind = 'MANAGED';
CREATE INDEX sys_identity_lifecycle_kind ON sys_identity_lifecycle (kind, user_id);
CREATE INDEX sys_identity_lifecycle_restriction ON sys_identity_lifecycle (restriction, user_id);

-- 历史只追加；退出受管不删除既有员工身份和复职边界。
CREATE TABLE sys_identity_lifecycle_history
(
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user (id),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('NONE', 'MANAGED', 'EMERGENCY')),
    source VARCHAR(32),
    enterprise_id VARCHAR(256),
    employee_id VARCHAR(256),
    connection_id VARCHAR(128),
    restriction VARCHAR(16) NOT NULL CHECK (restriction IN ('CLEAR', 'OFFBOARDED', 'REVIEW')),
    version BIGINT NOT NULL CHECK (version >= 0),
    last_event_id BIGINT,
    restored_at TIMESTAMPTZ,
    actor_user_id BIGINT REFERENCES sys_user (id),
    operation VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sys_identity_lifecycle_history_user
    ON sys_identity_lifecycle_history (user_id, id DESC);
CREATE INDEX sys_identity_lifecycle_history_employee
    ON sys_identity_lifecycle_history (source, enterprise_id, employee_id, id DESC);

-- 只保存不透明确认凭据的摘要，核对事实保留在服务端。
CREATE TABLE sys_identity_lifecycle_proof
(
    token_hash CHAR(64) PRIMARY KEY,
    actor_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    target_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('MAP', 'RESTORE', 'EMERGENCY')),
    binding_digest CHAR(64) NOT NULL,
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence) = 'object'),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);
CREATE INDEX sys_identity_lifecycle_proof_pending
    ON sys_identity_lifecycle_proof (target_user_id, operation, expires_at)
    WHERE consumed_at IS NULL;

-- 请求标识在操作者范围内唯一；不同操作/目标/内容复用同一标识必须冲突。
CREATE TABLE sys_identity_lifecycle_receipt
(
    actor_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    request_id VARCHAR(128) NOT NULL CHECK (length(btrim(request_id)) > 0),
    operation VARCHAR(32) NOT NULL,
    target_key VARCHAR(128) NOT NULL,
    binding_digest CHAR(64) NOT NULL,
    response JSONB NOT NULL CHECK (jsonb_typeof(response) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_user_id, request_id)
);
