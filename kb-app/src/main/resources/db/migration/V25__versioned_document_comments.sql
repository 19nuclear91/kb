-- 固定版本评论：正文、单层结构、墓碑、最小命令回执与无正文协作事实。
-- 前置：V23 审核与 V24 通知已执行。评论写入只由新版 app 产生；旧 app/Worker 仍只写审核与发布事实，
-- 新增列对其可空，不需要排空。本脚本不回填历史评论或发布证据，不修改旧迁移。

-- 评论正文与结构。内部 id 单调，仅用于排序与游标；对外只暴露 comment_id。
-- root_id 为空表示根评论；回复的根必须属于同一文档版本，且本身是根（触发器拒绝回复的回复）。
-- body 为 NULL 当且仅当已删除：删除清除正文，保留归属、结构与幂等墓碑。
CREATE TABLE kb_document_comment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    comment_id UUID NOT NULL UNIQUE,
    doc_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    root_id BIGINT,
    author_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    body TEXT CHECK (body IS NULL OR char_length(body) BETWEEN 1 AND 4000),
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY (version_id, doc_id) REFERENCES kb_document_version (id, doc_id),
    UNIQUE (id, doc_id, version_id),
    UNIQUE (comment_id, doc_id, version_id),
    FOREIGN KEY (root_id, doc_id, version_id) REFERENCES kb_document_comment (id, doc_id, version_id),
    CHECK (root_id IS NULL OR root_id < id),
    CHECK ((body IS NULL) = (deleted_at IS NOT NULL)),
    CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);
-- 根评论按版本倒序、回复按根正序分别有界分页。
CREATE INDEX idx_document_comment_roots ON kb_document_comment (doc_id, version_id, id DESC) WHERE root_id IS NULL;
CREATE INDEX idx_document_comment_replies ON kb_document_comment (root_id, id) WHERE root_id IS NOT NULL;

-- 最后防线：只允许回复未删除的同版本根评论；删除只能清除正文一次，其余列不可变，行不可物理删除。
CREATE FUNCTION protect_document_comment() RETURNS TRIGGER AS $$
DECLARE
    parent_root BIGINT;
    parent_deleted TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'comment tombstones are retained';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.body IS NULL OR NEW.deleted_at IS NOT NULL THEN
            RAISE EXCEPTION 'new comment requires body';
        END IF;
        IF NEW.root_id IS NOT NULL THEN
            SELECT root_id, deleted_at INTO parent_root, parent_deleted
            FROM kb_document_comment WHERE id = NEW.root_id;
            IF NOT FOUND OR parent_root IS NOT NULL OR parent_deleted IS NOT NULL THEN
                RAISE EXCEPTION 'reply requires a live root comment';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.comment_id, NEW.doc_id, NEW.version_id, NEW.root_id, NEW.author_user_id, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.comment_id, OLD.doc_id, OLD.version_id, OLD.root_id, OLD.author_user_id, OLD.created_at) THEN
        RAISE EXCEPTION 'comment identity is immutable';
    END IF;
    IF OLD.deleted_at IS NOT NULL OR NEW.deleted_at IS NULL OR NEW.body IS NOT NULL THEN
        RAISE EXCEPTION 'comments can only be erased once';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_document_comment_guard BEFORE INSERT OR UPDATE OR DELETE ON kb_document_comment
    FOR EACH ROW EXECUTE FUNCTION protect_document_comment();

-- 创建命令绑定：只保存规范化正文摘要与最小回执引用，不保存正文副本或原始请求。
-- 不对版本建外键，避免在文档锁之前对版本行取键共享锁；归属由同事务内的评论行外键约束。
CREATE TABLE kb_comment_command (
    operation_domain TEXT NOT NULL CHECK (operation_domain = 'COMMENT_CREATE'),
    request_id TEXT NOT NULL CHECK (length(request_id) BETWEEN 1 AND 128 AND request_id !~ '[[:cntrl:]]'),
    actor_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    doc_id BIGINT NOT NULL CHECK (doc_id > 0),
    version_id BIGINT NOT NULL CHECK (version_id > 0),
    parent_comment_id UUID,
    body_digest TEXT NOT NULL CHECK (body_digest ~ '^[0-9a-f]{64}$'),
    comment_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (operation_domain, request_id),
    FOREIGN KEY (comment_id, doc_id, version_id) REFERENCES kb_document_comment (comment_id, doc_id, version_id)
);

-- 绑定字段不可变；回执只能由空写为一次，且不可删除，避免删除后用新内容复用旧标识。
CREATE FUNCTION protect_comment_command() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'comment command bindings are retained';
    END IF;
    IF (NEW.operation_domain, NEW.request_id, NEW.actor_user_id, NEW.doc_id, NEW.version_id,
        NEW.parent_comment_id, NEW.body_digest, NEW.created_at)
        IS DISTINCT FROM (OLD.operation_domain, OLD.request_id, OLD.actor_user_id, OLD.doc_id, OLD.version_id,
        OLD.parent_comment_id, OLD.body_digest, OLD.created_at) THEN
        RAISE EXCEPTION 'comment command binding is immutable';
    END IF;
    IF OLD.comment_id IS NOT NULL OR NEW.comment_id IS NULL THEN
        RAISE EXCEPTION 'comment receipt is written once';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_comment_command_guard BEFORE UPDATE OR DELETE ON kb_comment_command
    FOR EACH ROW EXECUTE FUNCTION protect_comment_command();

-- 协作事实扩展评论种类。ADD COLUMN 不触发行级不可变触发器；旧事实两列均为空。
-- 删除与重建约束只修改元数据，不重写既有事实。约束名为 V23 未显式命名时 PostgreSQL 的确定性默认名；
-- 不使用 IF EXISTS，名称不符时迁移失败而不是静默保留旧约束。
ALTER TABLE kb_collaboration_event
    ADD COLUMN comment_id UUID,
    ADD COLUMN root_comment_id UUID;
ALTER TABLE kb_collaboration_event
    DROP CONSTRAINT kb_collaboration_event_kind_check,
    DROP CONSTRAINT kb_collaboration_event_check,
    ADD CONSTRAINT ck_collaboration_event_kind CHECK (kind IN ('REVIEW_SUBMITTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED',
        'REVIEW_WITHDRAWN', 'REVIEW_CANCELLED', 'VERSION_PUBLISHED', 'COMMENT_CREATED', 'COMMENT_REPLIED', 'COMMENT_DELETED')),
    ADD CONSTRAINT ck_collaboration_event_target CHECK (
        (kind IN ('COMMENT_CREATED', 'COMMENT_REPLIED', 'COMMENT_DELETED')
            AND review_id IS NULL AND round_no IS NULL AND submitted_at IS NULL AND decided_at IS NULL
            AND actor_user_id IS NOT NULL AND comment_id IS NOT NULL
            AND (kind <> 'COMMENT_CREATED' OR root_comment_id IS NULL)
            AND (kind <> 'COMMENT_REPLIED' OR root_comment_id IS NOT NULL)
            AND (root_comment_id IS NULL OR root_comment_id <> comment_id))
        OR (kind NOT IN ('COMMENT_CREATED', 'COMMENT_REPLIED', 'COMMENT_DELETED')
            AND comment_id IS NULL AND root_comment_id IS NULL
            AND ((review_id IS NULL AND round_no IS NULL AND kind = 'VERSION_PUBLISHED')
                OR (review_id IS NOT NULL AND round_no > 0 AND submitted_at IS NOT NULL)))),
    ADD CONSTRAINT fk_collaboration_event_comment FOREIGN KEY (comment_id, doc_id, version_id)
        REFERENCES kb_document_comment (comment_id, doc_id, version_id),
    ADD CONSTRAINT fk_collaboration_event_root_comment FOREIGN KEY (root_comment_id, doc_id, version_id)
        REFERENCES kb_document_comment (comment_id, doc_id, version_id);

-- COMMENT 覆盖起点由首个新版评论事实同事务写入；不回填历史，根创建与回复按 kind 分别计数。
ALTER TABLE kb_collaboration_coverage
    DROP CONSTRAINT kb_collaboration_coverage_domain_check,
    ADD CONSTRAINT ck_collaboration_coverage_domain CHECK (domain IN ('REVIEW', 'COMMENT'));
