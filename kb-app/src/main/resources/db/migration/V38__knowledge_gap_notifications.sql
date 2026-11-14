-- 问题来源独立派生，共用收件箱的身份、顺序和已读事实；不回填或复制旧通知。
CREATE TABLE kb_knowledge_gap_notification_projection (
    event_id UUID PRIMARY KEY REFERENCES kb_knowledge_gap_event (event_id),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'WAITING_SOURCE', 'DONE')),
    lease_owner TEXT CHECK (lease_owner IS NULL OR char_length(lease_owner) BETWEEN 1 AND 64),
    lease_until TIMESTAMPTZ,
    claim_token BIGINT NOT NULL DEFAULT 0 CHECK (claim_token >= 0),
    recipient_cursor BIGINT NOT NULL DEFAULT 0 CHECK (recipient_cursor >= 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_class TEXT CHECK (error_class IS NULL OR error_class IN ('SOURCE_NOT_READY', 'DERIVATION_FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((status = 'DONE') = (completed_at IS NOT NULL)),
    CHECK (status <> 'DONE' OR lease_owner IS NULL)
);
CREATE INDEX idx_gap_notification_projection_due
    ON kb_knowledge_gap_notification_projection (next_attempt_at, event_id) WHERE status <> 'DONE';

-- 两个 projection 具有相同的进度字段，复用 V24 不回退进度/已完成状态的保护函数。
CREATE TRIGGER trg_gap_notification_projection_guard
    BEFORE UPDATE OR DELETE ON kb_knowledge_gap_notification_projection
    FOR EACH ROW EXECUTE FUNCTION protect_notification_projection();

ALTER TABLE kb_user_notification
    ALTER COLUMN event_id DROP NOT NULL,
    ADD COLUMN gap_event_id UUID;
ALTER TABLE kb_user_notification
    DROP CONSTRAINT kb_user_notification_type_check,
    ADD CONSTRAINT ck_user_notification_source CHECK ((event_id IS NULL) <> (gap_event_id IS NULL)),
    ADD CONSTRAINT ck_user_notification_type_source CHECK (
        (event_id IS NOT NULL AND type IN ('REVIEW_SUBMITTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED', 'COMMENT_REPLIED'))
        OR (gap_event_id IS NOT NULL AND type IN ('GAP_CREATED', 'GAP_RETURNED', 'GAP_ASSIGNED',
            'GAP_SOLUTION_SUBMITTED', 'GAP_SOLUTION_REJECTED', 'GAP_RESOLVED', 'GAP_WITHDRAWN', 'GAP_CLOSED'))),
    ADD CONSTRAINT uq_user_notification_gap_source UNIQUE (gap_event_id, recipient_user_id),
    ADD CONSTRAINT fk_user_notification_gap_recipient FOREIGN KEY (gap_event_id, recipient_user_id)
        REFERENCES kb_knowledge_gap_event_recipient (event_id, user_id);

-- 不删除原 event_id/recipient 复合外键、唯一键、身份序列或本人收件箱索引。
-- gap_event_id 纳入不可变身份；隐藏通知仍只允许本人首次设置已读时间。
CREATE OR REPLACE FUNCTION protect_user_notification() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'user notification cleanup is not defined';
    END IF;
    IF (NEW.id, NEW.notification_id, NEW.event_id, NEW.gap_event_id, NEW.recipient_user_id, NEW.type, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.notification_id, OLD.event_id, OLD.gap_event_id, OLD.recipient_user_id, OLD.type, OLD.created_at) THEN
        RAISE EXCEPTION 'user notification identity is immutable';
    END IF;
    IF OLD.read_at IS NOT NULL AND NEW.read_at IS DISTINCT FROM OLD.read_at THEN
        RAISE EXCEPTION 'first read time is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
