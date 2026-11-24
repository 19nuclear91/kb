-- 空词典从修订零开始；所有业务写入统一锁定此行，搜索只读取一致快照。
CREATE TABLE kb_search_dictionary_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
INSERT INTO kb_search_dictionary_state (id, revision) VALUES (1, 0);

CREATE TABLE kb_search_term (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (id > 0),
    canonical_name TEXT NOT NULL CHECK (char_length(canonical_name) BETWEEN 1 AND 64),
    canonical_key TEXT COLLATE "C" NOT NULL UNIQUE CHECK (char_length(canonical_key) BETWEEN 1 AND 64),
    description TEXT NOT NULL CHECK (char_length(description) BETWEEN 1 AND 256),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kb_search_term_alias (
    term_id BIGINT NOT NULL REFERENCES kb_search_term (id) ON DELETE RESTRICT,
    alias_text TEXT NOT NULL CHECK (char_length(alias_text) BETWEEN 1 AND 64),
    lookup_key TEXT COLLATE "C" NOT NULL CHECK (char_length(lookup_key) BETWEEN 1 AND 64),
    kind TEXT NOT NULL CHECK (kind IN ('SYNONYM', 'ABBREVIATION', 'FORMER_NAME')),
    PRIMARY KEY (term_id, lookup_key)
);
CREATE INDEX idx_search_term_alias_lookup ON kb_search_term_alias (lookup_key, term_id);

CREATE TABLE kb_search_correction (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (id > 0),
    wrong_expression TEXT NOT NULL CHECK (char_length(wrong_expression) BETWEEN 1 AND 64),
    wrong_key TEXT COLLATE "C" NOT NULL UNIQUE CHECK (char_length(wrong_key) BETWEEN 1 AND 64),
    target_term_id BIGINT NOT NULL REFERENCES kb_search_term (id) ON DELETE RESTRICT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_search_correction_target ON kb_search_correction (target_term_id, id);

-- 回执的历史操作者及目标身份不随主体清理变化；请求标识在整个词典域唯一。
CREATE TABLE kb_search_term_command (
    request_id TEXT COLLATE "C" PRIMARY KEY
        CHECK (char_length(request_id) BETWEEN 1 AND 128 AND request_id !~ '[[:cntrl:]]'),
    operation TEXT NOT NULL CHECK (operation IN ('SEARCH_TERM_CREATE', 'SEARCH_TERM_UPDATE',
        'SEARCH_TERM_ENABLED', 'SEARCH_CORRECTION_CREATE', 'SEARCH_CORRECTION_UPDATE',
        'SEARCH_CORRECTION_ENABLED')),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    target_kind TEXT NOT NULL CHECK (target_kind IN ('TERM', 'CORRECTION')),
    target_id BIGINT CHECK (target_id > 0),
    expected_version BIGINT CHECK (expected_version >= 0),
    digest_version INTEGER NOT NULL CHECK (digest_version = 1),
    input_digest TEXT NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    receipt JSONB NOT NULL CHECK (jsonb_typeof(receipt) = 'object' AND octet_length(receipt::text) <= 4096),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((target_kind = 'TERM') = (operation IN ('SEARCH_TERM_CREATE', 'SEARCH_TERM_UPDATE', 'SEARCH_TERM_ENABLED'))),
    CHECK (CASE WHEN operation IN ('SEARCH_TERM_CREATE', 'SEARCH_CORRECTION_CREATE')
        THEN target_id IS NULL AND expected_version IS NULL
        ELSE target_id IS NOT NULL AND expected_version IS NOT NULL END)
);
CREATE FUNCTION reject_search_term_command_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'search terminology commands are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_search_term_command_immutable
    BEFORE UPDATE OR DELETE ON kb_search_term_command
    FOR EACH ROW EXECUTE FUNCTION reject_search_term_command_mutation();
CREATE TRIGGER trg_search_term_command_no_truncate
    BEFORE TRUNCATE ON kb_search_term_command
    FOR EACH STATEMENT EXECUTE FUNCTION reject_search_term_command_mutation();

-- 仅首次迁移绑定当时的管理员；后续启动和引导不补回已撤销的绑定。
INSERT INTO sys_role_permission (role_id, permission)
SELECT r.id, p.permission FROM sys_role r
CROSS JOIN (VALUES ('search:term:read'), ('search:term:write')) AS p(permission)
WHERE r.code = 'ROLE_ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
