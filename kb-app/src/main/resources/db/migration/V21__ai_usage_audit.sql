-- 聊天观测只保存元数据，业务主体和会话删除不级联到此记录。
CREATE TABLE kb_ai_call (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    call_id UUID NOT NULL UNIQUE,
    registration_digest CHAR(64) NOT NULL,
    mode VARCHAR(8) NOT NULL CHECK (mode IN ('SINGLE', 'MULTI')),
    turn_id UUID UNIQUE,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    department_id BIGINT NOT NULL CHECK (department_id > 0),
    protocol_version INTEGER NOT NULL CHECK (protocol_version = 1),
    started_at TIMESTAMPTZ NOT NULL,
    initiated_at TIMESTAMPTZ NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 1 CHECK (epoch > 0),
    version BIGINT NOT NULL DEFAULT 0,
    generation_status VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
        CHECK (generation_status IN ('RUNNING','COMPLETED','FAILED','CANCELLED','INTERRUPTED','REJECTED')),
    result_code VARCHAR(32),
    usage_status VARCHAR(16) CHECK (usage_status IN ('KNOWN','UNKNOWN','NOT_INVOKED')),
    prompt_tokens BIGINT CHECK (prompt_tokens >= 0),
    completion_tokens BIGINT CHECK (completion_tokens >= 0),
    quota_intended BOOLEAN NOT NULL DEFAULT false,
    model_dispatch_intent BOOLEAN NOT NULL DEFAULT false,
    lease_id UUID,
    reserved_tokens BIGINT CHECK (reserved_tokens >= 0),
    planned_charged_tokens BIGINT CHECK (planned_charged_tokens >= 0),
    settlement_policy VARCHAR(32) NOT NULL CHECK (settlement_policy IN ('SINGLE_EXISTING_V1','MULTI_EXISTING_V1')),
    settlement_status VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUIRED'
        CHECK (settlement_status IN ('NOT_REQUIRED','RESERVED','PENDING','SETTLED','REVIEW')),
    finalized_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    retention_anchor_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    CHECK ((mode = 'MULTI') = (turn_id IS NOT NULL)),
    CHECK ((usage_status IS NULL AND generation_status = 'RUNNING')
        OR (usage_status = 'KNOWN' AND prompt_tokens IS NOT NULL AND completion_tokens IS NOT NULL)
        OR (usage_status = 'UNKNOWN' AND prompt_tokens IS NULL AND completion_tokens IS NULL)
        OR (usage_status = 'NOT_INVOKED' AND prompt_tokens IS NOT NULL AND completion_tokens IS NOT NULL
            AND prompt_tokens = 0 AND completion_tokens = 0)),
    CHECK (usage_status IS NOT NULL OR generation_status = 'RUNNING'),
    CHECK (retention_anchor_at IS NULL OR
        (generation_status <> 'RUNNING' AND settlement_status IN ('SETTLED','NOT_REQUIRED') AND finalized_at IS NOT NULL))
);
CREATE INDEX kb_ai_call_time ON kb_ai_call (started_at, id);
CREATE INDEX kb_ai_call_user_time ON kb_ai_call (user_id, started_at);
CREATE INDEX kb_ai_call_department_time ON kb_ai_call (department_id, started_at);
CREATE INDEX kb_ai_call_expired ON kb_ai_call (lease_until) WHERE mode = 'SINGLE' AND generation_status = 'RUNNING';
CREATE INDEX kb_ai_call_unconfirmed ON kb_ai_call (finalized_at) WHERE mode = 'SINGLE' AND generation_status <> 'RUNNING' AND settlement_status = 'PENDING';
CREATE INDEX kb_ai_call_retention ON kb_ai_call (retention_anchor_at) WHERE retention_anchor_at IS NOT NULL;

CREATE TABLE kb_ai_call_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    call_id UUID NOT NULL REFERENCES kb_ai_call(call_id),
    kind VARCHAR(24) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    payload JSONB NOT NULL,
    UNIQUE (call_id, kind)
);
CREATE INDEX kb_ai_event_time ON kb_ai_call_event (occurred_at DESC, id DESC);
CREATE INDEX kb_ai_event_call_time ON kb_ai_call_event (call_id, occurred_at DESC, id DESC);

CREATE TABLE kb_ai_collection_period (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    protocol_version INTEGER NOT NULL DEFAULT 1,
    retention_days INTEGER NOT NULL CHECK (retention_days > 0),
    CHECK (closed_at IS NULL OR closed_at >= opened_at)
);
CREATE UNIQUE INDEX kb_ai_one_open_period ON kb_ai_collection_period ((1)) WHERE closed_at IS NULL;

-- 实例短期心跳仅用于拒绝不一致的清理配置，不包含调用资料。
CREATE TABLE kb_ai_observation_instance (
    instance_id UUID PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    retention_days INTEGER NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL
);

INSERT INTO sys_role_permission (role_id, permission)
SELECT r.id, p.permission FROM sys_role r
    CROSS JOIN (VALUES ('ai:usage:read'), ('ai:audit:read')) AS p(permission)
WHERE r.code = 'ROLE_ADMIN'
ON CONFLICT DO NOTHING;
