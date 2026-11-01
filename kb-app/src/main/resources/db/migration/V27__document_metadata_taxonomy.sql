-- 资料与文件版本独立；历史资料保持未分类、无标签及版本零。
CREATE TABLE kb_category_tree_state (
    space_id BIGINT PRIMARY KEY REFERENCES kb_space (id) ON DELETE CASCADE,
    tree_version BIGINT NOT NULL DEFAULT 0 CHECK (tree_version >= 0)
);
INSERT INTO kb_category_tree_state (space_id) SELECT id FROM kb_space;

CREATE TABLE kb_document_category (
    id BIGSERIAL PRIMARY KEY,
    space_id BIGINT NOT NULL REFERENCES kb_space (id) ON DELETE CASCADE,
    parent_id BIGINT,
    name TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 64),
    normalized_name TEXT COLLATE "C" NOT NULL CHECK (char_length(normalized_name) BETWEEN 1 AND 256),
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (space_id, id),
    CHECK (parent_id IS NULL OR parent_id <> id),
    FOREIGN KEY (space_id, parent_id) REFERENCES kb_document_category (space_id, id)
);
CREATE UNIQUE INDEX uq_document_category_root_name
    ON kb_document_category (space_id, normalized_name) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX uq_document_category_child_name
    ON kb_document_category (space_id, parent_id, normalized_name) WHERE parent_id IS NOT NULL;
CREATE INDEX idx_document_category_position ON kb_document_category (space_id, parent_id, sort_order, id);

ALTER TABLE kb_document
    ADD COLUMN summary TEXT CHECK (char_length(summary) <= 2000),
    ADD COLUMN category_id BIGINT,
    ADD COLUMN metadata_version BIGINT NOT NULL DEFAULT 0 CHECK (metadata_version >= 0),
    ADD CONSTRAINT fk_document_space_category FOREIGN KEY (space_id, category_id)
        REFERENCES kb_document_category (space_id, id);
CREATE INDEX idx_document_category ON kb_document (space_id, category_id);
CREATE INDEX idx_document_metadata_position ON kb_document (updated_at DESC, id DESC);

CREATE TABLE kb_document_tag (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 32),
    normalized_name TEXT COLLATE "C" NOT NULL UNIQUE CHECK (char_length(normalized_name) BETWEEN 1 AND 128),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE kb_document_tag_relation (
    document_id BIGINT NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES kb_document_tag (id),
    PRIMARY KEY (document_id, tag_id)
);
CREATE INDEX idx_document_tag_reverse ON kb_document_tag_relation (tag_id, document_id);

-- 回执保留历史身份，不能因空间或分类删除而级联丢失请求绑定。
CREATE TABLE kb_document_metadata_command (
    operation_scope TEXT NOT NULL CHECK (operation_scope IN (
        'DOCUMENT_METADATA_UPDATE', 'DOCUMENT_CATEGORY_CREATE', 'DOCUMENT_CATEGORY_UPDATE',
        'DOCUMENT_CATEGORY_DELETE', 'DOCUMENT_TAG_CREATE', 'DOCUMENT_TAG_UPDATE', 'DOCUMENT_RELOCATE')),
    request_id TEXT NOT NULL CHECK (char_length(request_id) BETWEEN 1 AND 128 AND request_id !~ '[[:cntrl:]]'),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    target_id BIGINT CHECK (target_id > 0),
    space_id BIGINT CHECK (space_id > 0),
    document_id BIGINT CHECK (document_id > 0),
    expected_version BIGINT CHECK (expected_version >= 0),
    digest_version INTEGER NOT NULL CHECK (digest_version = 1),
    input_digest TEXT NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    receipt JSONB NOT NULL CHECK (jsonb_typeof(receipt) = 'object' AND octet_length(receipt::text) <= 65536),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (operation_scope, request_id)
);
CREATE FUNCTION reject_document_metadata_command_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'document metadata commands are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_document_metadata_command_immutable
    BEFORE UPDATE OR DELETE ON kb_document_metadata_command
    FOR EACH ROW EXECUTE FUNCTION reject_document_metadata_command_mutation();
