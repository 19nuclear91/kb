-- 个人会话；正文与账本独立，文档来源不设级联外键以便撤权后安全失败。
CREATE TABLE kb_conversation
(
    id                BIGSERIAL PRIMARY KEY,
    owner_user_id     BIGINT       NOT NULL REFERENCES sys_user (id),
    title             VARCHAR(200),
    create_request_id VARCHAR(128) NOT NULL CHECK (char_length(create_request_id) BETWEEN 1 AND 128),
    creation_digest   CHAR(64)     NOT NULL,
    last_turn_seq     BIGINT       NOT NULL DEFAULT 0 CHECK (last_turn_seq >= 0),
    active_turn_id    UUID,
    execution_epoch   BIGINT       NOT NULL DEFAULT 0 CHECK (execution_epoch >= 0),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    UNIQUE (owner_user_id, create_request_id),
    CHECK ((deleted_at IS NULL AND title IS NOT NULL AND char_length(title) BETWEEN 1 AND 200)
        OR (deleted_at IS NOT NULL AND title IS NULL AND active_turn_id IS NULL))
);
CREATE INDEX kb_conversation_owner_page ON kb_conversation (owner_user_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE kb_conversation_turn
(
    id                      UUID PRIMARY KEY,
    conversation_id         BIGINT       NOT NULL REFERENCES kb_conversation (id),
    turn_seq                BIGINT       NOT NULL CHECK (turn_seq > 0),
    request_id              VARCHAR(128) NOT NULL CHECK (char_length(request_id) BETWEEN 1 AND 128),
    binding_digest          CHAR(64)     NOT NULL,
    question                TEXT,
    assistant_text          TEXT,
    status                  VARCHAR(16)  NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    answer_kind             VARCHAR(16) CHECK (answer_kind IN ('MODEL', 'NO_RESULTS')),
    source_sealed           BOOLEAN      NOT NULL DEFAULT false,
    expected_source_count   INTEGER      NOT NULL DEFAULT 0 CHECK (expected_source_count >= 0),
    source_digest           CHAR(64),
    seal_digest             CHAR(64),
    last_batch_seq          BIGINT       NOT NULL DEFAULT 0 CHECK (last_batch_seq >= 0),
    saved_codepoints        INTEGER      NOT NULL DEFAULT 0 CHECK (saved_codepoints >= 0),
    ticket_hash             CHAR(64),
    execution_ticket_digest CHAR(64)     NOT NULL,
    execution_epoch         BIGINT       NOT NULL CHECK (execution_epoch >= 0),
    lease_until             TIMESTAMPTZ,
    model_dispatch_intent   BOOLEAN      NOT NULL DEFAULT false,
    usage_status            VARCHAR(16)  NOT NULL DEFAULT 'NOT_INVOKED'
        CHECK (usage_status IN ('KNOWN', 'UNKNOWN', 'NOT_INVOKED')),
    prompt_tokens           BIGINT,
    completion_tokens       BIGINT,
    scrubbed                BOOLEAN      NOT NULL DEFAULT false,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at             TIMESTAMPTZ,
    UNIQUE (conversation_id, turn_seq),
    UNIQUE (conversation_id, request_id),
    UNIQUE (conversation_id, id),
    CHECK (saved_codepoints = char_length(COALESCE(assistant_text, ''))),
    CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
    CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
    CHECK ((usage_status = 'UNKNOWN' AND prompt_tokens IS NULL AND completion_tokens IS NULL)
        OR (usage_status = 'NOT_INVOKED' AND COALESCE(prompt_tokens, 0) = 0 AND COALESCE(completion_tokens, 0) = 0)
        OR (usage_status = 'KNOWN' AND prompt_tokens IS NOT NULL AND completion_tokens IS NOT NULL)),
    CHECK ((status = 'RUNNING' AND ticket_hash IS NOT NULL AND lease_until IS NOT NULL AND finished_at IS NULL)
        OR (status <> 'RUNNING' AND ticket_hash IS NULL AND lease_until IS NULL AND finished_at IS NOT NULL)),
    CHECK (status <> 'COMPLETED' OR source_sealed),
    CHECK (NOT scrubbed OR (question IS NULL AND assistant_text IS NULL AND saved_codepoints = 0)),
    CHECK (scrubbed OR question IS NOT NULL),
    CHECK (NOT source_sealed OR (source_digest IS NOT NULL AND seal_digest IS NOT NULL AND answer_kind IS NOT NULL)),
    CHECK (answer_kind IS DISTINCT FROM 'NO_RESULTS' OR expected_source_count = 0
)
    );
CREATE UNIQUE INDEX kb_conversation_single_running ON kb_conversation_turn (conversation_id) WHERE status = 'RUNNING';
CREATE INDEX kb_conversation_expired ON kb_conversation_turn (lease_until) WHERE status = 'RUNNING';
ALTER TABLE kb_conversation
    ADD CONSTRAINT kb_conversation_active_fk
        FOREIGN KEY (id, active_turn_id) REFERENCES kb_conversation_turn (conversation_id, id)
            DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE kb_conversation_source
(
    turn_id     UUID   NOT NULL REFERENCES kb_conversation_turn (id),
    document_id BIGINT NOT NULL CHECK (document_id > 0),
    version_id  BIGINT NOT NULL CHECK (version_id > 0),
    PRIMARY KEY (turn_id, document_id, version_id)
);
CREATE TABLE kb_conversation_citation
(
    turn_id           UUID    NOT NULL REFERENCES kb_conversation_turn (id),
    ordinal           INTEGER NOT NULL CHECK (ordinal >= 0),
    document_id       BIGINT  NOT NULL CHECK (document_id > 0),
    version_id        BIGINT  NOT NULL CHECK (version_id > 0),
    chunk_id          BIGINT  NOT NULL CHECK (chunk_id > 0),
    title             TEXT    NOT NULL,
    version_no        INTEGER,
    page_no           INTEGER,
    heading_path      TEXT,
    structure_type    TEXT,
    source_locator    TEXT,
    source_line_start INTEGER,
    source_line_end   INTEGER,
    source_char_start INTEGER,
    source_char_end   INTEGER,
    context_truncated BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (turn_id, ordinal),
    FOREIGN KEY (turn_id, document_id, version_id)
        REFERENCES kb_conversation_source (turn_id, document_id, version_id)
);
CREATE TABLE kb_conversation_batch
(
    turn_id    UUID     NOT NULL REFERENCES kb_conversation_turn (id),
    batch_seq  BIGINT   NOT NULL CHECK (batch_seq > 0),
    digest     CHAR(64) NOT NULL,
    codepoints INTEGER  NOT NULL CHECK (codepoints > 0),
    PRIMARY KEY (turn_id, batch_seq)
);
CREATE TABLE kb_conversation_settlement
(
    turn_id           UUID PRIMARY KEY REFERENCES kb_conversation_turn (id),
    user_id           BIGINT      NOT NULL,
    department_id     BIGINT      NOT NULL,
    lease_id          UUID        NOT NULL UNIQUE,
    event_id          UUID        NOT NULL UNIQUE,
    reserved_tokens   BIGINT      NOT NULL CHECK (reserved_tokens > 0),
    usage_status      VARCHAR(16) NOT NULL DEFAULT 'NOT_INVOKED'
        CHECK (usage_status IN ('KNOWN', 'UNKNOWN', 'NOT_INVOKED')),
    prompt_tokens     BIGINT,
    completion_tokens BIGINT,
    charged_tokens    BIGINT      NOT NULL DEFAULT 0 CHECK (charged_tokens >= 0),
    generation_status VARCHAR(16),
    intent_digest     CHAR(64),
    state             VARCHAR(16) NOT NULL DEFAULT 'RESERVED' CHECK (state IN ('RESERVED', 'PENDING', 'SETTLED', 'REVIEW')),
    attempts          INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    claimed_until     TIMESTAMPTZ,
    next_attempt_at   TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finalized_at      TIMESTAMPTZ,
    settled_at        TIMESTAMPTZ,
    CHECK ((usage_status = 'UNKNOWN' AND prompt_tokens IS NULL AND completion_tokens IS NULL AND
            charged_tokens = reserved_tokens)
        OR (usage_status = 'NOT_INVOKED' AND COALESCE(prompt_tokens, 0) = 0 AND COALESCE(completion_tokens, 0) = 0 AND
            charged_tokens = 0)
        OR (usage_status = 'KNOWN' AND prompt_tokens IS NOT NULL AND completion_tokens IS NOT NULL
            AND prompt_tokens >= 0 AND completion_tokens >= 0 AND charged_tokens = prompt_tokens + completion_tokens)),
    CHECK (state = 'RESERVED' OR
           (intent_digest IS NOT NULL AND finalized_at IS NOT NULL AND generation_status IS NOT NULL))
);
CREATE INDEX kb_conversation_settlement_pending ON kb_conversation_settlement (next_attempt_at) WHERE state = 'PENDING';

-- 删除时先清引用/来源，正文墓碑与资格撤销须在同一事务提交。
CREATE FUNCTION kb_conversation_deleted_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF
EXISTS (SELECT 1 FROM kb_conversation_turn t WHERE t.conversation_id = NEW.id
        AND (NOT t.scrubbed OR t.status = 'RUNNING'))
        OR EXISTS (SELECT 1 FROM kb_conversation_source s JOIN kb_conversation_turn t ON t.id = s.turn_id WHERE t.conversation_id = NEW.id)
        OR EXISTS (SELECT 1 FROM kb_conversation_citation c JOIN kb_conversation_turn t ON t.id = c.turn_id WHERE t.conversation_id = NEW.id) THEN
        RAISE EXCEPTION 'conversation deletion invariant';
END IF;
RETURN NEW;
END;
$$;
CREATE
CONSTRAINT TRIGGER kb_conversation_deleted_guard
    AFTER
UPDATE ON kb_conversation DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.deleted_at IS NOT NULL) EXECUTE FUNCTION kb_conversation_deleted_guard();

CREATE FUNCTION kb_conversation_turn_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF
OLD.status <> 'RUNNING' AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'terminal conversation turn';
END IF;
    IF
EXISTS (SELECT 1 FROM kb_conversation c WHERE c.id = NEW.conversation_id AND c.deleted_at IS NOT NULL)
        AND (NOT NEW.scrubbed OR NEW.status = 'RUNNING') THEN
        RAISE EXCEPTION 'deleted conversation';
END IF;
RETURN NEW;
END;
$$;
CREATE TRIGGER kb_conversation_turn_guard
    BEFORE UPDATE
    ON kb_conversation_turn
    FOR EACH ROW EXECUTE FUNCTION kb_conversation_turn_guard();

CREATE FUNCTION kb_conversation_active_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
cid BIGINT;
BEGIN
    IF
TG_TABLE_NAME = 'kb_conversation' THEN cid := NEW.id;
ELSE cid := NEW.conversation_id;
END IF;
    IF
EXISTS (SELECT 1 FROM kb_conversation c LEFT JOIN kb_conversation_turn t ON t.id = c.active_turn_id
        WHERE c.id = cid AND c.active_turn_id IS NOT NULL AND (t.status IS DISTINCT FROM 'RUNNING' OR t.execution_epoch <> c.execution_epoch))
        OR EXISTS (SELECT 1 FROM kb_conversation_turn t JOIN kb_conversation c ON c.id = t.conversation_id
            WHERE c.id = cid AND t.status = 'RUNNING' AND c.active_turn_id IS DISTINCT FROM t.id) THEN
        RAISE EXCEPTION 'conversation active invariant';
END IF;
RETURN NEW;
END;
$$;
CREATE
CONSTRAINT TRIGGER kb_conversation_active_guard AFTER INSERT OR
UPDATE ON kb_conversation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION kb_conversation_active_guard();
CREATE
CONSTRAINT TRIGGER kb_conversation_turn_active_guard AFTER INSERT OR
UPDATE ON kb_conversation_turn
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION kb_conversation_active_guard();
