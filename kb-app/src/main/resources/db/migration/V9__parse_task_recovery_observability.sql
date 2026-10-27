-- =====================================================================
-- V9: 解析任务恢复、死信历史与结构化事件
--
-- 保留 kb_parse_task 现有四种状态，新增字段和历史表把死信与被取消
-- 的 failed 任务区分开，保证旧 Worker 和客户端可以安全回滚。
-- =====================================================================

ALTER TABLE kb_parse_task
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN failure_class         TEXT,
    ADD COLUMN failure_code          TEXT,
    ADD COLUMN failure_summary       TEXT,
    ADD COLUMN last_failure_at       TIMESTAMPTZ,
    ADD COLUMN execution_generation  BIGINT NOT NULL DEFAULT 0;

ALTER TABLE kb_parse_task
    ADD CONSTRAINT ck_parse_task_failure_class CHECK (
        failure_class IS NULL OR failure_class IN ('PERMANENT', 'RETRYABLE', 'UNKNOWN')
        ),
    ADD CONSTRAINT ck_parse_task_failure_code CHECK (
        failure_code IS NULL OR length(btrim(failure_code)) BETWEEN 1 AND 128
    ),
    ADD CONSTRAINT ck_parse_task_failure_summary CHECK (
        failure_summary IS NULL OR length(failure_summary) <= 2000
    ),
    ADD CONSTRAINT ck_parse_task_generation_nonnegative CHECK (execution_generation >= 0);

UPDATE kb_parse_task
SET next_attempt_at      = COALESCE(next_attempt_at, updated_at, created_at),
    execution_generation = COALESCE(execution_generation, 0);

CREATE INDEX idx_parse_task_claim_ready
    ON kb_parse_task (status, next_attempt_at, id) WHERE status = 'pending';

CREATE INDEX idx_parse_task_failure_class
    ON kb_parse_task (failure_class, last_failure_at DESC);

-- 每个执行周期一条死信，保留管理员重放前的失败事实。
CREATE TABLE kb_parse_task_dead_letter
(
    id                   BIGSERIAL PRIMARY KEY,
    task_id              BIGINT      NOT NULL REFERENCES kb_parse_task (id) ON DELETE CASCADE,
    version_id           BIGINT      NOT NULL REFERENCES kb_document_version (id) ON DELETE CASCADE,
    execution_generation BIGINT      NOT NULL,
    attempts             INT         NOT NULL,
    failure_class        TEXT        NOT NULL CHECK (failure_class IN ('PERMANENT', 'RETRYABLE', 'UNKNOWN')),
    failure_code         TEXT        NOT NULL,
    error_summary        TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'REPLAYED', 'RESOLVED')),
    entered_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at          TIMESTAMPTZ,
    resolved_by          BIGINT REFERENCES sys_user (id),
    replay_request_id    TEXT,
    resolution_reason    TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_parse_dead_letter_attempts_nonnegative CHECK (attempts >= 0),
    CONSTRAINT ck_parse_dead_letter_code CHECK (length(btrim(failure_code)) BETWEEN 1 AND 128),
    CONSTRAINT ck_parse_dead_letter_summary CHECK (length(btrim(error_summary)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_parse_dead_letter_request CHECK (
        replay_request_id IS NULL OR length(btrim(replay_request_id)) BETWEEN 1 AND 256
        ),
    CONSTRAINT ck_parse_dead_letter_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolved_by IS NULL)
            OR (status <> 'OPEN' AND resolved_at IS NOT NULL)
        ),
    UNIQUE (task_id, execution_generation)
);

CREATE UNIQUE INDEX uq_parse_dead_letter_replay_request
    ON kb_parse_task_dead_letter (replay_request_id) WHERE replay_request_id IS NOT NULL;

CREATE INDEX idx_parse_dead_letter_open_time
    ON kb_parse_task_dead_letter (status, entered_at DESC);

CREATE INDEX idx_parse_dead_letter_version_time
    ON kb_parse_task_dead_letter (version_id, entered_at DESC);

-- 历史任务只补齐可查询的 legacy 死信事实，不写入告警事件，避免升级时产生通知风暴。
WITH exhausted AS (
UPDATE kb_parse_task
SET status          = 'failed',
    lease_until     = NULL,
    lease_owner     = NULL,
    failure_class   = COALESCE(failure_class, 'UNKNOWN'),
    failure_code    = COALESCE(failure_code, 'LEGACY_EXHAUSTED'),
    failure_summary = left (COALESCE (failure_summary, last_error, '历史解析任务已达到重试上限'), 2000), last_failure_at = COALESCE (last_failure_at, updated_at, now()), updated_at = now()
WHERE attempts >= max_attempts
  AND (status = 'pending'
   OR (status = 'running'
  AND lease_until
    < now()))
    RETURNING id
    , version_id
    , attempts
    , failure_class
    , failure_code
    , failure_summary
    , last_failure_at
    , execution_generation
    )
INSERT
INTO kb_parse_task_dead_letter
(task_id, version_id, execution_generation, attempts, failure_class,
 failure_code, error_summary, entered_at, created_at)
SELECT id,
       version_id,
       execution_generation,
       attempts,
       COALESCE(failure_class, 'UNKNOWN'),
       COALESCE(failure_code, 'LEGACY_EXHAUSTED'), left (COALESCE (failure_summary, '历史解析任务已达到重试上限'), 2000), COALESCE (last_failure_at, now()), COALESCE (last_failure_at, now())
FROM exhausted
ON CONFLICT (task_id, execution_generation) DO NOTHING;

INSERT INTO kb_parse_task_dead_letter
(task_id, version_id, execution_generation, attempts, failure_class,
 failure_code, error_summary, entered_at, created_at)
SELECT t.id,
       t.version_id,
       t.execution_generation,
       t.attempts,
       COALESCE(t.failure_class, 'UNKNOWN'),
       COALESCE(t.failure_code, 'LEGACY_FAILED'), left (COALESCE (t.failure_summary, t.last_error, '历史解析任务已失败'), 2000), COALESCE (t.last_failure_at, t.updated_at, t.created_at), COALESCE (t.last_failure_at, t.updated_at, t.created_at)
FROM kb_parse_task t
WHERE t.status = 'failed'
  AND COALESCE (t.last_error
    , '') <> '文档已删除'
ON CONFLICT (task_id, execution_generation) DO NOTHING;

-- 关键状态转移的追加式历史；摘要字段严格限长，不保存正文或完整堆栈。
CREATE TABLE kb_parse_task_event
(
    id                   BIGSERIAL PRIMARY KEY,
    task_id              BIGINT      NOT NULL REFERENCES kb_parse_task (id) ON DELETE CASCADE,
    version_id           BIGINT      NOT NULL REFERENCES kb_document_version (id) ON DELETE CASCADE,
    execution_generation BIGINT      NOT NULL,
    attempt_no           INT,
    event_type           TEXT        NOT NULL CHECK (event_type IN (
                                                                    'CLAIMED', 'RETRY_SCHEDULED', 'LEASE_LOST',
                                                                    'SUCCEEDED',
                                                                    'DEAD_LETTERED', 'REPLAYED', 'RESOLVED'
        )),
    failure_class        TEXT,
    failure_code         TEXT,
    error_summary        TEXT,
    actor_user_id        BIGINT REFERENCES sys_user (id),
    request_id           TEXT,
    worker_owner         TEXT,
    dedupe_key           TEXT UNIQUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_parse_task_event_attempt_nonnegative CHECK (
        attempt_no IS NULL OR attempt_no >= 0
        ),
    CONSTRAINT ck_parse_task_event_failure_class CHECK (
        failure_class IS NULL OR failure_class IN ('PERMANENT', 'RETRYABLE', 'UNKNOWN')
        ),
    CONSTRAINT ck_parse_task_event_code CHECK (
        failure_code IS NULL OR length(btrim(failure_code)) BETWEEN 1 AND 128
        ),
    CONSTRAINT ck_parse_task_event_summary CHECK (
        error_summary IS NULL OR length(error_summary) <= 2000
        ),
    CONSTRAINT ck_parse_task_event_request CHECK (
        request_id IS NULL OR length(btrim(request_id)) BETWEEN 1 AND 256
        )
);

CREATE INDEX idx_parse_task_event_task_time
    ON kb_parse_task_event (task_id, execution_generation, created_at DESC);

CREATE INDEX idx_parse_task_event_type_time
    ON kb_parse_task_event (event_type, created_at DESC);
