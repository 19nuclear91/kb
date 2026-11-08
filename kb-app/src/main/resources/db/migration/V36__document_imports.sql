-- 本人导入清单与项级受理账本；既有文档和版本不回填。
CREATE TABLE kb_document_import (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES sys_user(id),
    create_request_id TEXT NOT NULL CHECK (length(create_request_id) BETWEEN 1 AND 256),
    manifest_digest TEXT NOT NULL CHECK (manifest_digest ~ '^[0-9a-f]{64}$'),
    space_id BIGINT NOT NULL REFERENCES kb_space(id),
    security_level SMALLINT NOT NULL REFERENCES sys_security_level(level),
    item_count INTEGER NOT NULL CHECK (item_count BETWEEN 1 AND 500),
    total_bytes BIGINT NOT NULL CHECK (total_bytes > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (owner_user_id, create_request_id)
);
CREATE INDEX idx_import_owner_page ON kb_document_import(owner_user_id, id DESC);

CREATE TABLE kb_document_import_item (
    id BIGSERIAL PRIMARY KEY,
    import_id BIGINT NOT NULL REFERENCES kb_document_import(id),
    item_key TEXT NOT NULL CHECK (length(item_key) BETWEEN 1 AND 128),
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 500),
    file_name TEXT NOT NULL CHECK (length(btrim(file_name)) BETWEEN 1 AND 255),
    title TEXT NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 500),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'FAILED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    attempt_id BIGINT,
    document_id BIGINT REFERENCES kb_document(id),
    version_id BIGINT,
    parse_task_id BIGINT REFERENCES kb_parse_task(id),
    failure_code TEXT CHECK (failure_code IN ('INVALID_FILE', 'PAYLOAD_TOO_LARGE', 'STORAGE_UNAVAILABLE', 'EXPIRED', 'ACCESS_DENIED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (import_id, item_key),
    UNIQUE (import_id, ordinal),
    UNIQUE (version_id),
    UNIQUE (parse_task_id),
    FOREIGN KEY (version_id, document_id) REFERENCES kb_document_version(id, doc_id),
    CHECK ((state = 'ACCEPTED' AND document_id IS NOT NULL AND version_id IS NOT NULL AND parse_task_id IS NOT NULL)
        OR (state <> 'ACCEPTED' AND document_id IS NULL AND version_id IS NULL AND parse_task_id IS NULL)),
    CHECK ((state = 'FAILED') = (failure_code IS NOT NULL)),
    CHECK ((state = 'PENDING' AND attempt_id IS NULL) OR (state <> 'PENDING' AND attempt_id IS NOT NULL))
);

CREATE TABLE kb_document_import_attempt (
    id BIGSERIAL PRIMARY KEY,
    item_id BIGINT NOT NULL REFERENCES kb_document_import_item(id),
    request_id TEXT NOT NULL CHECK (length(request_id) BETWEEN 1 AND 256),
    binding_digest TEXT NOT NULL CHECK (binding_digest ~ '^[0-9a-f]{64}$'),
    generation BIGINT NOT NULL CHECK (generation > 0),
    object_key TEXT NOT NULL UNIQUE CHECK (object_key LIKE 'documents/%'),
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'ACCEPTED', 'INVALIDATED')),
    auth_epoch BIGINT NOT NULL CHECK (auth_epoch > 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_until TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    UNIQUE (item_id, request_id),
    UNIQUE (item_id, generation),
    UNIQUE (id, item_id),
    CHECK (lease_until > started_at),
    CHECK ((state = 'ACTIVE' AND ended_at IS NULL) OR (state <> 'ACTIVE' AND ended_at IS NOT NULL))
);
ALTER TABLE kb_document_import_item ADD CONSTRAINT fk_import_item_attempt
    FOREIGN KEY (attempt_id, id) REFERENCES kb_document_import_attempt(id, item_id);
CREATE UNIQUE INDEX uq_import_active_item ON kb_document_import_attempt(item_id) WHERE state = 'ACTIVE';
CREATE INDEX idx_import_attempt_recovery ON kb_document_import_attempt(lease_until, item_id) WHERE state = 'ACTIVE';

-- 已受理项不可逆，旧对象键和尝试身份不能被重新分配；原件清理由对账执行。
CREATE FUNCTION protect_import_item_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.import_id, NEW.item_key, NEW.ordinal, NEW.file_name, NEW.title, NEW.size_bytes, NEW.sha256)
       IS DISTINCT FROM
       (OLD.import_id, OLD.item_key, OLD.ordinal, OLD.file_name, OLD.title, OLD.size_bytes, OLD.sha256) THEN
        RAISE EXCEPTION 'Import manifest is immutable';
    END IF;
    IF OLD.state = 'ACCEPTED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Import receipt is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_import_item_receipt BEFORE UPDATE ON kb_document_import_item
    FOR EACH ROW EXECUTE FUNCTION protect_import_item_receipt();

CREATE FUNCTION protect_import_attempt_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.item_id, NEW.request_id, NEW.binding_digest, NEW.generation, NEW.object_key, NEW.auth_epoch, NEW.started_at, NEW.lease_until)
       IS DISTINCT FROM
       (OLD.item_id, OLD.request_id, OLD.binding_digest, OLD.generation, OLD.object_key, OLD.auth_epoch, OLD.started_at, OLD.lease_until)
       OR (OLD.state <> 'ACTIVE' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Import attempt identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_import_attempt_identity BEFORE UPDATE ON kb_document_import_attempt
    FOR EACH ROW EXECUTE FUNCTION protect_import_attempt_identity();
