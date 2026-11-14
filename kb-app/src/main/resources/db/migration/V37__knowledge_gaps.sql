-- 独立知识问题单；只持久化明确分享的人工文本，不保存会话或模型快照。
-- app 负责迁移；升级时排空不认识新权限目录的旧 app，不修改历史迁移。
CREATE TABLE kb_knowledge_gap (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    category TEXT NOT NULL CHECK (category IN ('MISSING_KNOWLEDGE', 'INACCURATE', 'OUTDATED', 'OTHER')),
    source TEXT NOT NULL CHECK (source IN ('MANUAL', 'SEARCH', 'CONVERSATION')),
    description TEXT NOT NULL CHECK (char_length(description) BETWEEN 1 AND 4000),
    consent_version TEXT NOT NULL CHECK (consent_version = 'GAP_SHARE_V1'),
    state TEXT NOT NULL CHECK (state IN ('SUBMITTED', 'IN_PROGRESS', 'AWAITING_CONFIRMATION', 'RESOLVED', 'CLOSED', 'WITHDRAWN')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    assignee_user_id BIGINT REFERENCES sys_user (id),
    assignment_epoch BIGINT NOT NULL DEFAULT 0 CHECK (assignment_epoch >= 0 AND assignment_epoch <= version),
    pending_solution_id UUID,
    pending_solution_kind TEXT NOT NULL DEFAULT 'SOLUTION' CHECK (pending_solution_kind = 'SOLUTION'),
    queue_event_id UUID,
    queue_event_is_queue BOOLEAN NOT NULL DEFAULT TRUE CHECK (queue_event_is_queue),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((state = 'SUBMITTED') = (queue_event_id IS NOT NULL)),
    CHECK ((state = 'AWAITING_CONFIRMATION') = (pending_solution_id IS NOT NULL)),
    CHECK (state <> 'SUBMITTED' OR assignee_user_id IS NULL),
    CHECK (state NOT IN ('IN_PROGRESS', 'AWAITING_CONFIRMATION') OR assignee_user_id IS NOT NULL),
    CHECK (assignee_user_id IS NULL OR assignment_epoch > 0),
    CHECK (updated_at >= created_at)
);

-- 单内顺序采用成功命令的结果版本，正文只在本表和首次描述中保留。
CREATE TABLE kb_knowledge_gap_entry (
    entry_id UUID PRIMARY KEY,
    gap_id BIGINT NOT NULL REFERENCES kb_knowledge_gap (id),
    position BIGINT NOT NULL CHECK (position > 0),
    kind TEXT NOT NULL CHECK (kind IN ('NOTE', 'SOLUTION', 'RETURN', 'REJECT_SOLUTION', 'WITHDRAW', 'CLOSE')),
    author_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 4000),
    document_id BIGINT,
    version_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (gap_id, position),
    UNIQUE (entry_id, gap_id),
    UNIQUE (entry_id, gap_id, kind),
    FOREIGN KEY (version_id, document_id) REFERENCES kb_document_version (id, doc_id),
    CHECK ((document_id IS NULL) = (version_id IS NULL)),
    CHECK (document_id IS NULL OR (kind = 'SOLUTION' AND document_id > 0 AND version_id > 0)),
    CHECK (kind = 'SOLUTION' OR char_length(body) <= 2000)
);

CREATE TABLE kb_knowledge_gap_event (
    event_id UUID PRIMARY KEY,
    gap_id BIGINT NOT NULL REFERENCES kb_knowledge_gap (id),
    kind TEXT NOT NULL CHECK (kind IN ('GAP_CREATED', 'GAP_ASSIGNED', 'NOTE_ADDED', 'GAP_RETURNED',
        'GAP_SOLUTION_SUBMITTED', 'GAP_SOLUTION_REJECTED', 'GAP_RESOLVED', 'GAP_WITHDRAWN', 'GAP_CLOSED')),
    result_version BIGINT NOT NULL CHECK (result_version >= 0),
    actor_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    assignee_user_id BIGINT REFERENCES sys_user (id),
    assignment_epoch BIGINT NOT NULL CHECK (assignment_epoch >= 0 AND assignment_epoch <= result_version),
    entry_id UUID,
    solution_id UUID,
    solution_kind TEXT NOT NULL DEFAULT 'SOLUTION' CHECK (solution_kind = 'SOLUTION'),
    is_queue BOOLEAN GENERATED ALWAYS AS (kind IN ('GAP_CREATED', 'GAP_RETURNED')) STORED,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (gap_id, result_version),
    UNIQUE (event_id, gap_id),
    UNIQUE (event_id, gap_id, is_queue),
    UNIQUE (event_id, gap_id, result_version),
    FOREIGN KEY (entry_id, gap_id) REFERENCES kb_knowledge_gap_entry (entry_id, gap_id),
    FOREIGN KEY (solution_id, gap_id, solution_kind) REFERENCES kb_knowledge_gap_entry (entry_id, gap_id, kind),
    CHECK ((kind = 'GAP_CREATED') = (result_version = 0)),
    CHECK (kind <> 'GAP_CREATED' OR (assignee_user_id IS NULL AND assignment_epoch = 0)),
    CHECK ((kind IN ('NOTE_ADDED', 'GAP_RETURNED', 'GAP_SOLUTION_SUBMITTED', 'GAP_SOLUTION_REJECTED',
        'GAP_WITHDRAWN', 'GAP_CLOSED')) = (entry_id IS NOT NULL)),
    CHECK ((kind IN ('GAP_SOLUTION_SUBMITTED', 'GAP_SOLUTION_REJECTED', 'GAP_RESOLVED')) = (solution_id IS NOT NULL)),
    CHECK (kind <> 'GAP_SOLUTION_SUBMITTED' OR entry_id = solution_id),
    CHECK (kind NOT IN ('GAP_ASSIGNED', 'GAP_SOLUTION_SUBMITTED', 'GAP_SOLUTION_REJECTED', 'GAP_RESOLVED')
        OR (assignee_user_id IS NOT NULL AND assignment_epoch > 0))
);

-- 当前方案必须是同单 SOLUTION；受理信号必须是同单创建或退回事件。
-- 创建预分配 UUID，循环引用到提交时才校验，不临时关闭约束。
ALTER TABLE kb_knowledge_gap
    ADD CONSTRAINT fk_gap_pending_solution FOREIGN KEY (pending_solution_id, id, pending_solution_kind)
        REFERENCES kb_knowledge_gap_entry (entry_id, gap_id, kind) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_gap_queue_event FOREIGN KEY (queue_event_id, id, queue_event_is_queue)
        REFERENCES kb_knowledge_gap_event (event_id, gap_id, is_queue) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE kb_knowledge_gap_event_recipient (
    event_id UUID NOT NULL REFERENCES kb_knowledge_gap_event (event_id),
    user_id BIGINT NOT NULL REFERENCES sys_user (id),
    PRIMARY KEY (event_id, user_id)
);

-- 命令绑定先于问题锁；目标不加立即外键，避免提前获取问题键共享锁。
-- CREATE 的绑定 gap_id 为空，实际结果身份写入 result_gap_id。
CREATE TABLE kb_knowledge_gap_command (
    request_id TEXT PRIMARY KEY CHECK (char_length(request_id) BETWEEN 1 AND 128 AND request_id !~ '[[:cntrl:]]'),
    actor_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    operation TEXT NOT NULL CHECK (operation IN ('CREATE', 'ASSIGN', 'NOTE', 'RETURN', 'SOLUTION',
        'CONFIRM', 'REJECT_SOLUTION', 'WITHDRAW', 'CLOSE')),
    gap_id BIGINT CHECK (gap_id > 0),
    expected_version BIGINT CHECK (expected_version >= 0),
    input_digest TEXT NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    result_gap_id BIGINT,
    result_version BIGINT,
    result_state TEXT CHECK (result_state IN ('SUBMITTED', 'IN_PROGRESS', 'AWAITING_CONFIRMATION', 'RESOLVED', 'CLOSED', 'WITHDRAWN')),
    result_event_id UUID,
    result_solution_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((operation = 'CREATE' AND gap_id IS NULL AND expected_version IS NULL)
        OR (operation <> 'CREATE' AND gap_id IS NOT NULL AND expected_version IS NOT NULL)),
    CHECK ((result_gap_id IS NULL AND result_version IS NULL AND result_state IS NULL
            AND result_event_id IS NULL AND result_solution_id IS NULL)
        OR (result_gap_id IS NOT NULL AND result_version IS NOT NULL AND result_state IS NOT NULL
            AND result_event_id IS NOT NULL)),
    CHECK (result_gap_id IS NULL OR operation = 'CREATE' OR result_gap_id = gap_id),
    CHECK (result_version IS NULL OR result_version >= 0),
    FOREIGN KEY (result_event_id, result_gap_id, result_version)
        REFERENCES kb_knowledge_gap_event (event_id, gap_id, result_version) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (result_solution_id, result_gap_id)
        REFERENCES kb_knowledge_gap_entry (entry_id, gap_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE kb_knowledge_gap_actor_guard (
    reporter_user_id BIGINT PRIMARY KEY REFERENCES sys_user (id)
);

CREATE INDEX idx_gap_reporter_page ON kb_knowledge_gap (reporter_user_id, id DESC);
CREATE INDEX idx_gap_assignee_page ON kb_knowledge_gap (assignee_user_id, id DESC) WHERE assignee_user_id IS NOT NULL;
CREATE INDEX idx_gap_state_page ON kb_knowledge_gap (state, id DESC);
CREATE INDEX idx_gap_reporter_open ON kb_knowledge_gap (reporter_user_id, id)
    WHERE state IN ('SUBMITTED', 'IN_PROGRESS', 'AWAITING_CONFIRMATION');
CREATE INDEX idx_gap_event_position ON kb_knowledge_gap_event (occurred_at, event_id);

CREATE FUNCTION protect_knowledge_gap() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge gap records are retained';
    END IF;
    IF (NEW.id, NEW.reporter_user_id, NEW.category, NEW.source, NEW.description, NEW.consent_version,
        NEW.pending_solution_kind, NEW.queue_event_is_queue, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.reporter_user_id, OLD.category, OLD.source, OLD.description, OLD.consent_version,
        OLD.pending_solution_kind, OLD.queue_event_is_queue, OLD.created_at) THEN
        RAISE EXCEPTION 'knowledge gap origin is immutable';
    END IF;
    IF OLD.state IN ('RESOLVED', 'CLOSED', 'WITHDRAWN') THEN
        RAISE EXCEPTION 'terminal knowledge gap cannot change';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'knowledge gap version must advance once';
    END IF;
    IF NEW.assignee_user_id IS NOT NULL AND NEW.assignee_user_id IS DISTINCT FROM OLD.assignee_user_id THEN
        IF NEW.assignment_epoch <> OLD.assignment_epoch + 1 THEN
            RAISE EXCEPTION 'knowledge gap reassignment must advance epoch';
        END IF;
    ELSIF NEW.assignment_epoch <> OLD.assignment_epoch THEN
        RAISE EXCEPTION 'knowledge gap assignment epoch is immutable without reassignment';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_knowledge_gap_guard BEFORE UPDATE OR DELETE ON kb_knowledge_gap
    FOR EACH ROW EXECUTE FUNCTION protect_knowledge_gap();

CREATE FUNCTION reject_knowledge_gap_fact_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'knowledge gap facts are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_gap_entry_immutable BEFORE UPDATE OR DELETE ON kb_knowledge_gap_entry
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_gap_fact_mutation();
CREATE TRIGGER trg_gap_event_immutable BEFORE UPDATE OR DELETE ON kb_knowledge_gap_event
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_gap_fact_mutation();
CREATE TRIGGER trg_gap_recipient_immutable BEFORE UPDATE OR DELETE ON kb_knowledge_gap_event_recipient
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_gap_fact_mutation();

CREATE FUNCTION protect_knowledge_gap_command() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge gap command bindings are retained';
    END IF;
    IF (NEW.request_id, NEW.actor_user_id, NEW.operation, NEW.gap_id, NEW.expected_version, NEW.input_digest, NEW.created_at)
        IS DISTINCT FROM (OLD.request_id, OLD.actor_user_id, OLD.operation, OLD.gap_id, OLD.expected_version, OLD.input_digest, OLD.created_at) THEN
        RAISE EXCEPTION 'knowledge gap command binding is immutable';
    END IF;
    IF OLD.result_event_id IS NOT NULL OR NEW.result_event_id IS NULL THEN
        RAISE EXCEPTION 'knowledge gap receipt is written once';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_gap_command_guard BEFORE UPDATE OR DELETE ON kb_knowledge_gap_command
    FOR EACH ROW EXECUTE FUNCTION protect_knowledge_gap_command();

-- 只在首次升级给当时已有管理员绑定；重启或重复引导不得补回已撤销资格。
INSERT INTO sys_role_permission (role_id, permission)
SELECT r.id, p.permission FROM sys_role r
CROSS JOIN (VALUES ('knowledge:gap:read'), ('knowledge:gap:assign'),
    ('knowledge:gap:handle'), ('knowledge:gap:close')) AS p(permission)
WHERE r.code = 'ROLE_ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
