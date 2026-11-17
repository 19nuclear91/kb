-- 结构快照属于派生数据；说明、历史与回执不随版本级联删除。
CREATE TABLE kb_document_version_snapshot (
    version_id BIGINT PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    execution_generation BIGINT NOT NULL CHECK (execution_generation >= 0),
    parser_name TEXT,
    parser_version TEXT,
    truncated BOOLEAN NOT NULL,
    block_count INTEGER NOT NULL CHECK (block_count >= 0),
    char_count BIGINT NOT NULL CHECK (char_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (version_id, doc_id) REFERENCES kb_document_version (id, doc_id) ON DELETE CASCADE
);

CREATE TABLE kb_document_version_block (
    version_id BIGINT NOT NULL REFERENCES kb_document_version_snapshot (version_id) ON DELETE CASCADE,
    block_idx INTEGER NOT NULL CHECK (block_idx >= 0),
    kind TEXT NOT NULL CHECK (kind IN ('PARAGRAPH', 'HEADING', 'LIST_ITEM', 'TABLE', 'CODE', 'UNKNOWN')),
    text TEXT NOT NULL CHECK (char_length(text) > 0),
    heading_path TEXT,
    page_no INTEGER,
    line_start INTEGER,
    line_end INTEGER,
    char_start INTEGER,
    char_end INTEGER,
    locator TEXT,
    table_cells JSONB,
    PRIMARY KEY (version_id, block_idx),
    CHECK (table_cells IS NULL OR jsonb_typeof(table_cells) = 'array'),
    CHECK (kind = 'TABLE' OR table_cells IS NULL)
);

CREATE TABLE kb_document_change_note (
    version_id BIGINT PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    text TEXT CHECK (char_length(text) BETWEEN 1 AND 2000),
    base_version_id BIGINT,
    note_version BIGINT NOT NULL CHECK (note_version > 0),
    corrected BOOLEAN NOT NULL DEFAULT FALSE,
    created_after_visible BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (version_id, doc_id) REFERENCES kb_document_version (id, doc_id),
    FOREIGN KEY (base_version_id, doc_id) REFERENCES kb_document_version (id, doc_id),
    CHECK ((text IS NULL) = (base_version_id IS NULL)),
    CHECK (base_version_id IS NULL OR base_version_id <> version_id)
);

CREATE TABLE kb_document_change_note_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id BIGINT NOT NULL,
    doc_id BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    text_before TEXT CHECK (char_length(text_before) BETWEEN 1 AND 2000),
    text_after TEXT CHECK (char_length(text_after) BETWEEN 1 AND 2000),
    base_before BIGINT,
    base_after BIGINT,
    visible_to_readers BOOLEAN NOT NULL,
    reason TEXT CHECK (char_length(reason) BETWEEN 1 AND 2000),
    FOREIGN KEY (version_id, doc_id) REFERENCES kb_document_version (id, doc_id),
    FOREIGN KEY (base_before, doc_id) REFERENCES kb_document_version (id, doc_id),
    FOREIGN KEY (base_after, doc_id) REFERENCES kb_document_version (id, doc_id),
    CHECK ((text_before IS NULL) = (base_before IS NULL)),
    CHECK ((text_after IS NULL) = (base_after IS NULL)),
    CHECK (NOT (visible_to_readers AND text_before IS NOT NULL) OR reason IS NOT NULL)
);
CREATE INDEX idx_change_note_history_time
    ON kb_document_change_note_history (version_id, changed_at DESC, id DESC);

CREATE TABLE kb_document_change_note_command (
    operation_scope TEXT NOT NULL CHECK (operation_scope = 'DOCUMENT_CHANGE_NOTE_UPDATE'),
    request_id TEXT NOT NULL CHECK (char_length(request_id) BETWEEN 1 AND 128 AND request_id !~ '[[:cntrl:]]'),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    target_id BIGINT NOT NULL CHECK (target_id > 0),
    document_id BIGINT NOT NULL CHECK (document_id > 0),
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    digest_version INTEGER NOT NULL CHECK (digest_version = 1),
    input_digest TEXT NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    receipt JSONB NOT NULL CHECK (jsonb_typeof(receipt) = 'object' AND octet_length(receipt::text) <= 65536),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (operation_scope, request_id)
);

CREATE FUNCTION reject_document_change_note_fact_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'document change note facts are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_change_note_history_immutable BEFORE UPDATE OR DELETE ON kb_document_change_note_history
    FOR EACH ROW EXECUTE FUNCTION reject_document_change_note_fact_mutation();
CREATE TRIGGER trg_change_note_command_immutable BEFORE UPDATE OR DELETE ON kb_document_change_note_command
    FOR EACH ROW EXECUTE FUNCTION reject_document_change_note_fact_mutation();
