-- 升级前必须排空旧 app/Worker；本脚本不支持新旧写入协议混部。
ALTER TABLE kb_space
    ADD COLUMN publication_policy TEXT NOT NULL DEFAULT 'AUTO'
        CHECK (publication_policy IN ('AUTO', 'REVIEW_REQUIRED'));

ALTER TABLE kb_document_version
    ADD COLUMN publication_policy TEXT,
    ADD COLUMN publication_protocol TEXT,
    ADD COLUMN uploader_user_id BIGINT REFERENCES sys_user (id),
    ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    ADD COLUMN publication_evidence TEXT NOT NULL DEFAULT 'NONE',
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN indexed_profile_id TEXT,
    ADD COLUMN indexed_fingerprint TEXT,
    ADD COLUMN indexed_epoch BIGINT CHECK (indexed_epoch >= 0),
    ADD COLUMN indexed_chunk_count INTEGER CHECK (indexed_chunk_count >= 0);

-- indexed 不能证明历史发布。旧上传者和发布时间不得用 owner/created_at 猜测。
UPDATE kb_document_version SET publication_policy = 'AUTO', publication_protocol = 'LEGACY_COMPAT';
UPDATE kb_document_version v SET publication_evidence = 'OBSERVED_CURRENT'
FROM kb_document d WHERE d.id = v.doc_id AND d.current_version_id = v.id AND d.status = 'published';

ALTER TABLE kb_document_version
    ALTER COLUMN publication_policy SET NOT NULL,
    ALTER COLUMN publication_protocol SET NOT NULL,
    ADD CONSTRAINT ck_version_publication_policy CHECK (publication_policy IN ('AUTO', 'REVIEW_REQUIRED')),
    ADD CONSTRAINT ck_version_publication_protocol CHECK (publication_protocol IN ('LEGACY_COMPAT', 'VERSIONED_REVIEW')),
    ADD CONSTRAINT ck_version_publication_evidence CHECK (publication_evidence IN ('NONE', 'PUBLISHED', 'OBSERVED_CURRENT')),
    ADD CONSTRAINT ck_version_uploader CHECK (publication_protocol = 'LEGACY_COMPAT' OR uploader_user_id IS NOT NULL),
    ADD CONSTRAINT ck_version_publication_time CHECK ((publication_evidence = 'PUBLISHED') = (published_at IS NOT NULL)),
    ADD CONSTRAINT ck_version_observed_legacy CHECK (publication_evidence <> 'OBSERVED_CURRENT' OR publication_protocol = 'LEGACY_COMPAT'),
    ADD CONSTRAINT fk_version_index_identity FOREIGN KEY (indexed_profile_id, indexed_fingerprint)
        REFERENCES kb_embedding_profile (profile_id, fingerprint),
    ADD CONSTRAINT ck_version_index_identity CHECK (
        (indexed_profile_id IS NULL AND indexed_fingerprint IS NULL AND indexed_epoch IS NULL AND indexed_chunk_count IS NULL)
        OR (indexed_profile_id IS NOT NULL AND indexed_fingerprint IS NOT NULL AND indexed_epoch IS NOT NULL AND indexed_chunk_count IS NOT NULL));

-- 没有 legacy 默认值；即使旧应用显式填写兼容标记，也不能创建新行。
CREATE FUNCTION protect_version_publication_origin() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.publication_protocol IS DISTINCT FROM 'VERSIONED_REVIEW'
            OR NEW.uploader_user_id IS NULL OR NEW.publication_evidence <> 'NONE' THEN
            RAISE EXCEPTION 'new version requires versioned publication protocol';
        END IF;
    ELSE
        IF (NEW.publication_policy, NEW.publication_protocol, NEW.uploader_user_id)
            IS DISTINCT FROM (OLD.publication_policy, OLD.publication_protocol, OLD.uploader_user_id) THEN
            RAISE EXCEPTION 'version publication origin is immutable';
        END IF;
        IF OLD.publication_evidence <> 'NONE'
            AND (NEW.publication_evidence, NEW.published_at) IS DISTINCT FROM (OLD.publication_evidence, OLD.published_at) THEN
            RAISE EXCEPTION 'publication evidence is immutable';
        END IF;
        IF OLD.publication_evidence = 'NONE' AND NEW.publication_evidence = 'OBSERVED_CURRENT' THEN
            RAISE EXCEPTION 'observed publication is upgrade-only';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_version_publication_origin BEFORE INSERT OR UPDATE ON kb_document_version
    FOR EACH ROW EXECUTE FUNCTION protect_version_publication_origin();

CREATE TABLE kb_document_review (
    id UUID PRIMARY KEY,
    doc_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    round_no INTEGER NOT NULL CHECK (round_no > 0),
    submitted_by BIGINT NOT NULL REFERENCES sys_user (id),
    -- 保留当次归属事实，搬迁后不以历史引用阻止原空空间删除。
    submitted_space_id BIGINT NOT NULL CHECK (submitted_space_id > 0),
    state TEXT NOT NULL CHECK (state IN ('IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED')),
    state_version BIGINT NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decision_by BIGINT REFERENCES sys_user (id),
    decided_at TIMESTAMPTZ,
    reason TEXT,
    FOREIGN KEY (version_id, doc_id) REFERENCES kb_document_version (id, doc_id),
    UNIQUE (version_id, round_no),
    UNIQUE (id, doc_id, version_id),
    UNIQUE (id, doc_id, version_id, round_no),
    CHECK ((state = 'IN_REVIEW' AND decided_at IS NULL AND decision_by IS NULL AND reason IS NULL)
        OR (state <> 'IN_REVIEW' AND decided_at IS NOT NULL)),
    CHECK ((state = 'REJECTED' AND reason IS NOT NULL AND length(btrim(reason)) BETWEEN 1 AND 2000)
        OR (state <> 'REJECTED' AND reason IS NULL)),
    CHECK (state NOT IN ('APPROVED', 'REJECTED', 'WITHDRAWN') OR decision_by IS NOT NULL),
    CHECK (state <> 'WITHDRAWN' OR decision_by = submitted_by)
);
CREATE UNIQUE INDEX uq_review_active_version ON kb_document_review (version_id) WHERE state = 'IN_REVIEW';
CREATE INDEX idx_review_pending_position ON kb_document_review (submitted_at, id) WHERE state = 'IN_REVIEW';
CREATE INDEX idx_review_submitter ON kb_document_review (submitted_by, version_id);
CREATE INDEX idx_version_uploader ON kb_document_version (uploader_user_id, id);
CREATE INDEX idx_version_auto_candidate ON kb_document_version (id)
    WHERE publication_policy = 'AUTO' AND publication_evidence = 'NONE' AND index_status = 'indexed';

CREATE TABLE kb_review_command_receipt (
    operation_domain TEXT NOT NULL CHECK (operation_domain IN ('POLICY', 'SUBMIT', 'APPROVE', 'REJECT', 'WITHDRAW', 'INDEX_REFRESH')),
    request_id TEXT NOT NULL CHECK (length(request_id) BETWEEN 1 AND 128 AND request_id !~ '[[:cntrl:]]'),
    actor_user_id BIGINT NOT NULL REFERENCES sys_user (id),
    space_id BIGINT CHECK (space_id > 0),
    doc_id BIGINT,
    version_id BIGINT,
    review_id UUID,
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    input_digest TEXT NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    receipt JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (operation_domain, request_id),
    FOREIGN KEY (version_id, doc_id) REFERENCES kb_document_version (id, doc_id),
    FOREIGN KEY (review_id, doc_id, version_id) REFERENCES kb_document_review (id, doc_id, version_id),
    CHECK ((operation_domain = 'POLICY' AND space_id IS NOT NULL AND doc_id IS NULL AND version_id IS NULL AND review_id IS NULL)
        OR (operation_domain <> 'POLICY' AND space_id IS NULL AND doc_id IS NOT NULL AND version_id IS NOT NULL)),
    CHECK (operation_domain NOT IN ('APPROVE', 'REJECT', 'WITHDRAW') OR review_id IS NOT NULL)
);

CREATE TABLE kb_collaboration_event (
    event_id UUID PRIMARY KEY,
    protocol_version INTEGER NOT NULL CHECK (protocol_version = 1),
    kind TEXT NOT NULL CHECK (kind IN ('REVIEW_SUBMITTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED',
        'REVIEW_WITHDRAWN', 'REVIEW_CANCELLED', 'VERSION_PUBLISHED')),
    business_key TEXT NOT NULL UNIQUE,
    doc_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    review_id UUID,
    round_no INTEGER,
    actor_user_id BIGINT REFERENCES sys_user (id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    FOREIGN KEY (version_id, doc_id) REFERENCES kb_document_version (id, doc_id),
    FOREIGN KEY (review_id, doc_id, version_id, round_no) REFERENCES kb_document_review (id, doc_id, version_id, round_no),
    CHECK ((review_id IS NULL AND round_no IS NULL AND kind = 'VERSION_PUBLISHED')
        OR (review_id IS NOT NULL AND round_no > 0 AND submitted_at IS NOT NULL)),
    CHECK (kind NOT IN ('REVIEW_APPROVED', 'REVIEW_REJECTED', 'REVIEW_WITHDRAWN', 'REVIEW_CANCELLED') OR decided_at IS NOT NULL)
);
CREATE INDEX idx_collaboration_event_position ON kb_collaboration_event (occurred_at, event_id);
CREATE TABLE kb_collaboration_event_recipient (
    event_id UUID NOT NULL REFERENCES kb_collaboration_event (event_id),
    user_id BIGINT NOT NULL REFERENCES sys_user (id),
    PRIMARY KEY (event_id, user_id)
);
CREATE TABLE kb_collaboration_coverage (
    domain TEXT PRIMARY KEY CHECK (domain = 'REVIEW'),
    protocol_version INTEGER NOT NULL CHECK (protocol_version = 1),
    started_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION reject_collaboration_fact_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'collaboration facts are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_collaboration_event_immutable BEFORE UPDATE OR DELETE ON kb_collaboration_event
    FOR EACH ROW EXECUTE FUNCTION reject_collaboration_fact_mutation();
CREATE TRIGGER trg_collaboration_recipient_immutable BEFORE UPDATE OR DELETE ON kb_collaboration_event_recipient
    FOR EACH ROW EXECUTE FUNCTION reject_collaboration_fact_mutation();
CREATE TRIGGER trg_collaboration_coverage_immutable BEFORE UPDATE OR DELETE ON kb_collaboration_coverage
    FOR EACH ROW EXECUTE FUNCTION reject_collaboration_fact_mutation();
