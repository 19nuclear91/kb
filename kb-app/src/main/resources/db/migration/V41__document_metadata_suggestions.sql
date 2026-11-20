-- 最小请求事实独立于文档及短期结果，以便删除文档、建议过期后仍可核对结算与幂等。
CREATE TABLE kb_document_metadata_suggestion (
    request_id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE,
    actor_id BIGINT NOT NULL CHECK (actor_id > 0),
    auth_epoch BIGINT NOT NULL CHECK (auth_epoch > 0),
    department_id BIGINT NOT NULL CHECK (department_id > 0),
    session_digest TEXT CHECK (session_digest ~ '^[0-9a-f]{64}$'),
    doc_id BIGINT NOT NULL CHECK (doc_id > 0),
    space_id BIGINT NOT NULL CHECK (space_id > 0),
    source_version_id BIGINT NOT NULL CHECK (source_version_id > 0),
    metadata_version BIGINT NOT NULL CHECK (metadata_version >= 0),
    tree_version BIGINT NOT NULL CHECK (tree_version >= 0),
    snapshot_task_id BIGINT,
    snapshot_generation BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deadline TIMESTAMPTZ NOT NULL,
    generation_status TEXT NOT NULL DEFAULT 'RUNNING'
        CHECK (generation_status IN ('RUNNING','COMPLETED','FAILED','CANCELLED','INTERRUPTED','REJECTED')),
    reason_code TEXT CHECK (reason_code ~ '^[A-Z_]{1,64}$'),
    dispatched_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    reserved_tokens BIGINT CHECK (reserved_tokens > 0),
    usage_status TEXT CHECK (usage_status IN ('NOT_INVOKED','KNOWN','UNKNOWN')),
    prompt_tokens BIGINT CHECK (prompt_tokens >= 0),
    completion_tokens BIGINT CHECK (completion_tokens >= 0),
    planned_charge BIGINT CHECK (planned_charge >= 0),
    settled_charge BIGINT CHECK (settled_charge >= 0),
    next_settlement_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    settlement_status TEXT NOT NULL DEFAULT 'NOT_REQUIRED'
        CHECK (settlement_status IN ('NOT_REQUIRED','RESERVING','RESERVED','PENDING','SETTLED','REVIEW')),
    CHECK ((generation_status = 'RUNNING') = (finished_at IS NULL)),
    CHECK (dispatched_at IS NULL OR reserved_tokens IS NOT NULL),
    CHECK (CASE usage_status
        WHEN 'KNOWN' THEN dispatched_at IS NOT NULL AND prompt_tokens IS NOT NULL AND completion_tokens IS NOT NULL
            AND planned_charge IS NOT NULL AND planned_charge::numeric = prompt_tokens::numeric + completion_tokens
        WHEN 'UNKNOWN' THEN dispatched_at IS NOT NULL AND prompt_tokens IS NULL AND completion_tokens IS NULL
            AND planned_charge IS NOT NULL AND planned_charge = reserved_tokens
        WHEN 'NOT_INVOKED' THEN dispatched_at IS NULL AND prompt_tokens IS NOT DISTINCT FROM 0
            AND completion_tokens IS NOT DISTINCT FROM 0 AND planned_charge IS NOT DISTINCT FROM 0
        ELSE prompt_tokens IS NULL AND completion_tokens IS NULL AND planned_charge IS NULL END),
    CHECK ((settlement_status = 'SETTLED') = (settled_charge IS NOT NULL)),
    CHECK (settled_charge IS NULL OR settled_charge IS NOT DISTINCT FROM planned_charge)
);
CREATE INDEX idx_metadata_suggestion_recovery ON kb_document_metadata_suggestion (deadline, request_id)
    WHERE usage_status IS NULL;
CREATE INDEX idx_metadata_suggestion_settle ON kb_document_metadata_suggestion (next_settlement_at, request_id)
    WHERE settlement_status = 'PENDING';

CREATE TABLE kb_document_metadata_suggestion_result (
    request_id UUID PRIMARY KEY REFERENCES kb_document_metadata_suggestion (request_id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 65536)
);
CREATE INDEX idx_metadata_suggestion_expiry ON kb_document_metadata_suggestion_result (expires_at, request_id);

-- 每次状态转换仅投影固定类别、身份和数值，派生正文只进入短期结果表。
CREATE TABLE kb_document_metadata_suggestion_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES kb_document_metadata_suggestion (request_id),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    fact JSONB NOT NULL
);
CREATE INDEX idx_metadata_suggestion_event_request ON kb_document_metadata_suggestion_event (request_id, id);
CREATE FUNCTION record_metadata_suggestion_event() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO kb_document_metadata_suggestion_event (request_id, fact)
    VALUES (NEW.request_id, jsonb_build_object('actorId', NEW.actor_id, 'departmentId', NEW.department_id,
        'documentId', NEW.doc_id, 'sourceVersionId', NEW.source_version_id, 'metadataVersion', NEW.metadata_version,
        'generationStatus', NEW.generation_status, 'reasonCode', NEW.reason_code, 'dispatchedAt', NEW.dispatched_at,
        'usageStatus', NEW.usage_status, 'promptTokens', NEW.prompt_tokens, 'completionTokens', NEW.completion_tokens,
        'reservedTokens', NEW.reserved_tokens, 'plannedCharge', NEW.planned_charge, 'settledCharge', NEW.settled_charge,
        'settlementStatus', NEW.settlement_status));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_metadata_suggestion_event AFTER INSERT OR UPDATE OF generation_status, reason_code, dispatched_at,
    usage_status, reserved_tokens, settlement_status ON kb_document_metadata_suggestion
    FOR EACH ROW EXECUTE FUNCTION record_metadata_suggestion_event();

ALTER TABLE kb_document_metadata_command DROP CONSTRAINT kb_document_metadata_command_operation_scope_check;
ALTER TABLE kb_document_metadata_command ADD CONSTRAINT kb_document_metadata_command_operation_scope_check
    CHECK (operation_scope IN ('DOCUMENT_METADATA_UPDATE','DOCUMENT_CATEGORY_CREATE','DOCUMENT_CATEGORY_UPDATE',
        'DOCUMENT_CATEGORY_DELETE','DOCUMENT_TAG_CREATE','DOCUMENT_TAG_UPDATE','DOCUMENT_RELOCATE',
        'DOCUMENT_METADATA_SUGGESTION_APPLY'));
