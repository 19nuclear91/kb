-- 站内通知：只从已提交协作事实派生，保存最小元数据，不复制标题、正文、意见、原因或凭据。
-- 源事件、派生进度与本人已读事实三者分离；本脚本不修改 V23 协作事实的 schema 语义。

-- 派生进度：每个源事件至多一条；lease/claim_token 只用于数据库时钟下的接管判定。
CREATE TABLE kb_notification_projection (
    event_id UUID PRIMARY KEY REFERENCES kb_collaboration_event (event_id),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'WAITING_SOURCE', 'DONE')),
    lease_owner TEXT CHECK (lease_owner IS NULL OR length(lease_owner) BETWEEN 1 AND 64),
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
-- 待领取工作：按到期时间有界扫描，完成项不进入该索引。
CREATE INDEX idx_notification_projection_due ON kb_notification_projection (next_attempt_at, event_id)
    WHERE status <> 'DONE';

CREATE FUNCTION protect_notification_projection() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'notification projection is retained';
    END IF;
    IF OLD.status = 'DONE' AND NEW.status <> 'DONE' THEN
        RAISE EXCEPTION 'completed notification projection cannot reopen';
    END IF;
    IF NEW.event_id <> OLD.event_id OR NEW.claim_token < OLD.claim_token
        OR NEW.recipient_cursor < OLD.recipient_cursor THEN
        RAISE EXCEPTION 'notification projection progress is monotonic';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_notification_projection_guard BEFORE UPDATE OR DELETE ON kb_notification_projection
    FOR EACH ROW EXECUTE FUNCTION protect_notification_projection();

-- 本人通知：唯一 (event_id, recipient_user_id)；收件人必须是源事务记录的提醒意图。
-- COMMENT_REPLIED 为后续评论生产者预留，本迁移不创建评论表或占位事件。
CREATE TABLE kb_user_notification (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id UUID NOT NULL UNIQUE,
    event_id UUID NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('REVIEW_SUBMITTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED', 'COMMENT_REPLIED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ,
    UNIQUE (event_id, recipient_user_id),
    FOREIGN KEY (event_id, recipient_user_id)
        REFERENCES kb_collaboration_event_recipient (event_id, user_id)
);
-- 本人列表按内部单调 ID 倒序；未读计数使用部分索引，不做全收件箱内存计算。
CREATE INDEX idx_user_notification_inbox ON kb_user_notification (recipient_user_id, id DESC);
CREATE INDEX idx_user_notification_unread ON kb_user_notification (recipient_user_id, id DESC)
    WHERE read_at IS NULL;

-- 只允许首次设置已读时间；其余列不可变，首期不提供删除或暗删。
CREATE FUNCTION protect_user_notification() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'user notification cleanup is not defined';
    END IF;
    IF (NEW.id, NEW.notification_id, NEW.event_id, NEW.recipient_user_id, NEW.type, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.notification_id, OLD.event_id, OLD.recipient_user_id, OLD.type, OLD.created_at) THEN
        RAISE EXCEPTION 'user notification identity is immutable';
    END IF;
    IF OLD.read_at IS NOT NULL AND NEW.read_at IS DISTINCT FROM OLD.read_at THEN
        RAISE EXCEPTION 'first read time is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_user_notification_guard BEFORE UPDATE OR DELETE ON kb_user_notification
    FOR EACH ROW EXECUTE FUNCTION protect_user_notification();
