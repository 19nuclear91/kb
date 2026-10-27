CREATE TABLE kb_embedding_profile
(
    profile_id  TEXT PRIMARY KEY CHECK (length(btrim(profile_id)) > 0),
    fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'
) ,
    provider TEXT NOT NULL,
    model_source TEXT NOT NULL,
    model TEXT NOT NULL,
    model_revision TEXT NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension = 1024),
    distance TEXT NOT NULL CHECK (distance = 'cosine'),
    query_transform_version TEXT NOT NULL,
    document_transform_version TEXT NOT NULL,
    query_task_type TEXT NOT NULL,
    document_task_type TEXT NOT NULL,
    adapter_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id, fingerprint)
);

CREATE FUNCTION reject_embedding_profile_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE
EXCEPTION 'embedding profile is immutable';
END;
$$
LANGUAGE plpgsql;

CREATE TRIGGER trg_embedding_profile_immutable
    BEFORE UPDATE OR
DELETE
ON kb_embedding_profile
    FOR EACH ROW EXECUTE FUNCTION reject_embedding_profile_mutation();

ALTER TABLE kb_doc_chunk
    ADD COLUMN embedding_profile_id TEXT REFERENCES kb_embedding_profile (profile_id);

CREATE TABLE kb_embedding_rebuild
(
    run_id            UUID PRIMARY KEY,
    target_profile_id TEXT        NOT NULL REFERENCES kb_embedding_profile (profile_id),
    created_by        BIGINT      NOT NULL REFERENCES sys_user (id),
    state             TEXT        NOT NULL CHECK (state IN
                                                  ('QUIESCING', 'REBUILDING', 'VERIFYING', 'BLOCKED', 'SUCCEEDED')),
    version           BIGINT      NOT NULL DEFAULT 0 CHECK (version >= 0),
    resume_phase      TEXT CHECK (resume_phase IN ('QUIESCING', 'REBUILDING', 'VERIFYING')),
    claim_token       BIGINT      NOT NULL DEFAULT 0 CHECK (claim_token >= 0),
    lease_owner       UUID,
    lease_until       TIMESTAMPTZ,
    attempts          INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts      INTEGER     NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    next_attempt_at   TIMESTAMPTZ,
    total_count       BIGINT      NOT NULL DEFAULT 0 CHECK (total_count >= 0),
    succeeded_count   BIGINT      NOT NULL DEFAULT 0 CHECK (succeeded_count >= 0),
    skipped_count     BIGINT      NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),
    error_code        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((state = 'BLOCKED' AND resume_phase IS NOT NULL) OR (state <> 'BLOCKED' AND resume_phase IS NULL)),
    CHECK ((state = 'SUCCEEDED') = (completed_at IS NOT NULL)),
    CHECK (succeeded_count + skipped_count <= total_count)
);

CREATE UNIQUE INDEX uq_embedding_unfinished_run
    ON kb_embedding_rebuild ((1)) WHERE state <> 'SUCCEEDED';

CREATE TABLE kb_embedding_control
(
    id                SMALLINT PRIMARY KEY CHECK (id = 1),
    state             TEXT   NOT NULL CHECK (state IN
                                             ('UNINITIALIZED', 'QUIESCING', 'REBUILDING', 'VERIFYING', 'BLOCKED',
                                              'ACTIVE')),
    active_profile_id TEXT REFERENCES kb_embedding_profile (profile_id),
    target_profile_id TEXT REFERENCES kb_embedding_profile (profile_id),
    epoch             BIGINT NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    run_id            UUID REFERENCES kb_embedding_rebuild (run_id),
    CHECK (
        (state = 'UNINITIALIZED' AND active_profile_id IS NULL AND target_profile_id IS NULL AND run_id IS NULL)
            OR (state = 'ACTIVE' AND active_profile_id IS NOT NULL AND target_profile_id IS NULL AND run_id IS NULL)
            OR (state IN ('QUIESCING', 'REBUILDING', 'VERIFYING', 'BLOCKED') AND target_profile_id IS NOT NULL AND
                run_id IS NOT NULL)
        )
);

INSERT INTO kb_embedding_control (id, state, epoch)
VALUES (1, 'UNINITIALIZED', 0);

CREATE TABLE kb_embedding_rebuild_item
(
    run_id         UUID   NOT NULL REFERENCES kb_embedding_rebuild (run_id),
    chunk_id       BIGINT NOT NULL,
    doc_id         BIGINT NOT NULL,
    version_id     BIGINT NOT NULL,
    content_digest TEXT   NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'
) ,
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    total_attempts BIGINT NOT NULL DEFAULT 0 CHECK (total_attempts >= attempts),
    claim_token BIGINT NOT NULL DEFAULT 0 CHECK (claim_token >= 0),
    run_claim_token BIGINT,
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    error_code TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, chunk_id),
    CHECK ((state = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL AND run_claim_token IS NOT NULL)
        OR (state <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL AND run_claim_token IS NULL))
);

CREATE INDEX idx_embedding_item_claim ON kb_embedding_rebuild_item (run_id, state, next_attempt_at, chunk_id);

CREATE TABLE kb_embedding_operation
(
    operation_id     UUID PRIMARY KEY,
    operation_domain TEXT NOT NULL CHECK (operation_domain IN ('START', 'RESUME', 'BLOCKED', 'SUCCEEDED')),
    request_id       TEXT NOT NULL CHECK (length(request_id) BETWEEN 1 AND 128),
    actor_user_id    BIGINT REFERENCES sys_user (id),
    input_digest     TEXT NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'
) ,
    run_id UUID NOT NULL REFERENCES kb_embedding_rebuild(run_id),
    reason TEXT CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500),
    receipt JSONB NOT NULL,
    before_state TEXT NOT NULL,
    after_state TEXT NOT NULL,
    epoch BIGINT NOT NULL CHECK (epoch >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (operation_domain, request_id),
    CHECK (operation_domain NOT IN ('START', 'RESUME') OR actor_user_id IS NOT NULL)
);

CREATE TABLE kb_embedding_readiness
(
    instance_id      UUID        NOT NULL,
    role             TEXT        NOT NULL CHECK (role IN ('QUERY', 'DOCUMENT')),
    profile_id       TEXT        NOT NULL,
    fingerprint      TEXT        NOT NULL,
    protocol_version INTEGER     NOT NULL CHECK (protocol_version > 0),
    ready            BOOLEAN     NOT NULL,
    checked_at       TIMESTAMPTZ NOT NULL,
    valid_until      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (instance_id, role, profile_id),
    FOREIGN KEY (profile_id, fingerprint) REFERENCES kb_embedding_profile (profile_id, fingerprint),
    CHECK (valid_until > checked_at)
);

CREATE INDEX idx_embedding_readiness_profile ON kb_embedding_readiness (profile_id, role, valid_until);

ALTER TABLE kb_parse_task
    ADD COLUMN embedding_profile_id TEXT REFERENCES kb_embedding_profile (profile_id),
    ADD COLUMN embedding_epoch BIGINT CHECK (embedding_epoch >= 0),
    ADD COLUMN claim_token BIGINT NOT NULL DEFAULT 0 CHECK (claim_token >= 0),
    ADD COLUMN maintenance_deferred_token BIGINT CHECK (maintenance_deferred_token >= 0),
    ADD CONSTRAINT ck_parse_embedding_identity_pair CHECK (
        (embedding_profile_id IS NULL) = (embedding_epoch IS NULL)
    );

ALTER TABLE kb_parse_task_event DROP CONSTRAINT kb_parse_task_event_event_type_check;
ALTER TABLE kb_parse_task_event
    ADD CONSTRAINT kb_parse_task_event_event_type_check CHECK (event_type IN (
                                                                              'CLAIMED', 'RETRY_SCHEDULED',
                                                                              'LEASE_LOST', 'SUCCEEDED',
                                                                              'DEAD_LETTERED', 'REPLAYED', 'RESOLVED',
                                                                              'MAINTENANCE_DEFERRED'
        ));
