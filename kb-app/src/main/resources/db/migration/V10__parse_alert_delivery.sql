-- V10: 解析结构化告警投递箱。Worker 只写事实，投递器异步调用外部适配器。
CREATE TABLE kb_parse_alert_delivery
(
    id                   BIGSERIAL PRIMARY KEY,
    task_id              BIGINT      NOT NULL REFERENCES kb_parse_task (id) ON DELETE CASCADE,
    version_id           BIGINT      NOT NULL REFERENCES kb_document_version (id) ON DELETE CASCADE,
    execution_generation BIGINT      NOT NULL,
    event_type           TEXT        NOT NULL CHECK (event_type IN (
                                                                    'DEAD_LETTERED', 'LEASE_LOST', 'QUEUE_AGING',
                                                                    'DELIVERY_FAILED'
        )),
    failure_class        TEXT,
    failure_code         TEXT,
    error_summary        TEXT,
    attempts             INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    status               TEXT        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENDING', 'SENT')),
    dedupe_key           TEXT        NOT NULL UNIQUE,
    next_attempt_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at              TIMESTAMPTZ
);

CREATE INDEX idx_parse_alert_delivery_ready
    ON kb_parse_alert_delivery (status, next_attempt_at, id) WHERE status <> 'SENT';
