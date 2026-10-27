-- =====================================================================
-- V5: 文档摄取领域完整性约束
--
-- V1/V2 先落了可运行的骨架；本迁移补齐文档、版本、分块和任务之间
-- 最容易产生脏数据的边界。应用层校验负责友好提示，数据库约束负责
-- 在并发、脚本导入或故障恢复路径上守住最终一致性。
-- =====================================================================

-- 文档当前版本必须属于同一篇文档，不能只靠 current_version_id 的单列外键。
ALTER TABLE kb_document_version
    ADD CONSTRAINT uq_document_version_id_doc UNIQUE (id, doc_id);

ALTER TABLE kb_document
    ADD CONSTRAINT fk_document_current_version_owner
        FOREIGN KEY (current_version_id, id)
            REFERENCES kb_document_version (id, doc_id);

-- 分块的 doc_id/version_id 必须成对匹配，避免错误版本的内容写入文档。
ALTER TABLE kb_doc_chunk
    ADD CONSTRAINT fk_chunk_version_owner
        FOREIGN KEY (version_id, doc_id)
            REFERENCES kb_document_version (id, doc_id);

-- 对象 key 是原件的物理身份，不能出现两个版本共用同一个 key。
DROP INDEX IF EXISTS idx_document_version_object_key;
ALTER TABLE kb_document_version
    ADD CONSTRAINT uq_document_version_object_key UNIQUE (object_key);

ALTER TABLE kb_document
    ADD CONSTRAINT ck_document_title_not_blank
        CHECK (length(btrim(title)) BETWEEN 1 AND 500),
    ADD CONSTRAINT ck_document_security_level_range
        CHECK (security_level BETWEEN 0 AND 3);

ALTER TABLE kb_document_version
    ADD CONSTRAINT ck_document_version_number_positive
        CHECK (version_no > 0),
    ADD CONSTRAINT ck_document_version_file_size_nonnegative
        CHECK (file_size >= 0),
    ADD CONSTRAINT ck_document_version_file_name_valid
        CHECK (length(btrim(file_name)) BETWEEN 1 AND 255),
    ADD CONSTRAINT ck_document_version_object_key_valid
        CHECK (length(btrim(object_key)) BETWEEN 1 AND 1024),
    ADD CONSTRAINT ck_document_version_hash_not_blank
        CHECK (length(btrim(content_hash)) BETWEEN 1 AND 128);

ALTER TABLE kb_doc_chunk
    ADD CONSTRAINT ck_doc_chunk_index_nonnegative CHECK (chunk_idx >= 0),
    ADD CONSTRAINT ck_doc_chunk_content_not_blank CHECK (length(btrim(content)) > 0);

ALTER TABLE kb_parse_task
    ADD CONSTRAINT ck_parse_task_attempts_nonnegative CHECK (attempts >= 0),
    ADD CONSTRAINT ck_parse_task_max_attempts_positive CHECK (max_attempts > 0),
    ADD CONSTRAINT ck_parse_task_lease_owner_pair CHECK (
        (lease_until IS NULL AND lease_owner IS NULL)
        OR (lease_until IS NOT NULL AND lease_owner IS NOT NULL)
    );

CREATE INDEX idx_document_published_current_version
    ON kb_document (current_version_id) WHERE status = 'published';

CREATE INDEX idx_document_version_doc_created
    ON kb_document_version (doc_id, created_at DESC);

-- 统一维护审计时间，避免只在应用路径更新而脚本/后台任务遗漏。
CREATE
OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at
= now();
RETURN NEW;
END;
$$
LANGUAGE plpgsql;

CREATE TRIGGER trg_document_updated_at
    BEFORE UPDATE
    ON kb_document
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_document_version_updated_at
    BEFORE UPDATE
    ON kb_document_version
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_parse_task_updated_at
    BEFORE UPDATE
    ON kb_parse_task
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
