-- 适用期跟随固定版本，复审跟随文档；存量不回填，不改动既有表。
CREATE TABLE kb_document_version_applicability (
    version_id BIGINT PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    effective_from DATE,
    until_kind TEXT NOT NULL CHECK (until_kind IN ('UNDECLARED', 'OPEN_ENDED', 'DATE')),
    effective_until DATE,
    applicability_version BIGINT NOT NULL CHECK (applicability_version > 0),
    corrected BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (version_id, doc_id) REFERENCES kb_document_version (id, doc_id),
    CHECK ((until_kind = 'DATE') = (effective_until IS NOT NULL)),
    CHECK (effective_from IS NULL OR effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'),
    CHECK (effective_until IS NULL OR effective_until BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'),
    CHECK (effective_from IS NULL OR effective_until IS NULL OR effective_until >= effective_from)
);

-- 操作者只进入审计；此表的原因对可访问该版本的读者可见。
CREATE TABLE kb_document_applicability_change (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id BIGINT NOT NULL,
    doc_id BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    before_effective_from DATE,
    before_until_kind TEXT NOT NULL CHECK (before_until_kind IN ('UNDECLARED', 'OPEN_ENDED', 'DATE')),
    before_effective_until DATE,
    after_effective_from DATE,
    after_until_kind TEXT NOT NULL CHECK (after_until_kind IN ('UNDECLARED', 'OPEN_ENDED', 'DATE')),
    after_effective_until DATE,
    visible_to_readers BOOLEAN NOT NULL,
    reason TEXT CHECK (char_length(reason) BETWEEN 1 AND 2000),
    FOREIGN KEY (version_id, doc_id) REFERENCES kb_document_version (id, doc_id),
    CHECK (NOT visible_to_readers OR reason IS NOT NULL),
    CHECK ((before_until_kind = 'DATE') = (before_effective_until IS NOT NULL)),
    CHECK ((after_until_kind = 'DATE') = (after_effective_until IS NOT NULL)),
    CHECK (before_effective_from IS NULL OR before_effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'),
    CHECK (before_effective_until IS NULL OR before_effective_until BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'),
    CHECK (after_effective_from IS NULL OR after_effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'),
    CHECK (after_effective_until IS NULL OR after_effective_until BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'),
    CHECK (before_effective_from IS NULL OR before_effective_until IS NULL OR before_effective_until >= before_effective_from),
    CHECK (after_effective_from IS NULL OR after_effective_until IS NULL OR after_effective_until >= after_effective_from)
);
CREATE INDEX idx_applicability_change_version ON kb_document_applicability_change (version_id, id DESC);
CREATE INDEX idx_applicability_change_time ON kb_document_applicability_change (version_id, changed_at DESC, id DESC);

CREATE TABLE kb_document_stewardship (
    doc_id BIGINT PRIMARY KEY REFERENCES kb_document (id),
    responsible_user_id BIGINT REFERENCES sys_user (id),
    cycle_months INTEGER NOT NULL CHECK (cycle_months IN (3, 6, 12, 24)),
    first_assigned_on DATE NOT NULL,
    last_confirmed_on DATE,
    last_confirmed_by BIGINT REFERENCES sys_user (id),
    due_date DATE NOT NULL,
    stewardship_version BIGINT NOT NULL CHECK (stewardship_version > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((last_confirmed_on IS NULL) = (last_confirmed_by IS NULL))
);
CREATE INDEX idx_stewardship_responsible_due ON kb_document_stewardship (responsible_user_id, due_date, doc_id)
    WHERE responsible_user_id IS NOT NULL;

-- 仿照资料命令：先用事务 advisory lock 绑定请求，成功时一次性追加完整回执。
-- 不引用目标外键，避免请求绑定提前获取业务行锁，也避免删除改变历史回执。
CREATE TABLE kb_document_validity_command (
    operation_scope TEXT NOT NULL CHECK (operation_scope IN (
        'DOCUMENT_APPLICABILITY_UPDATE', 'DOCUMENT_STEWARDSHIP_UPDATE', 'DOCUMENT_STEWARDSHIP_CONFIRM')),
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

CREATE FUNCTION reject_document_validity_fact_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'document validity facts are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_applicability_change_immutable BEFORE UPDATE OR DELETE ON kb_document_applicability_change
    FOR EACH ROW EXECUTE FUNCTION reject_document_validity_fact_mutation();
CREATE TRIGGER trg_validity_command_immutable BEFORE UPDATE OR DELETE ON kb_document_validity_command
    FOR EACH ROW EXECUTE FUNCTION reject_document_validity_fact_mutation();
