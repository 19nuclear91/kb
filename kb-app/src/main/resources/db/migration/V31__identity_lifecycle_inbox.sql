-- 原始传输 messageId 不参与业务去重；规范化事件与员工处置长期保留。
CREATE TABLE sys_identity_lifecycle_event
(
    id BIGSERIAL PRIMARY KEY,
    source_scope VARCHAR(128) NOT NULL,
    event_id VARCHAR(256) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    enterprise_id VARCHAR(256) NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    born_at TIMESTAMPTZ NOT NULL,
    content_digest CHAR(64) NOT NULL,
    conflicted BOOLEAN NOT NULL DEFAULT FALSE,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_scope, event_id)
);
CREATE INDEX sys_identity_lifecycle_event_page ON sys_identity_lifecycle_event (id DESC);

CREATE TABLE sys_identity_lifecycle_item
(
    event_id BIGINT NOT NULL REFERENCES sys_identity_lifecycle_event (id),
    employee_id VARCHAR(256) NOT NULL,
    user_id BIGINT REFERENCES sys_user (id),
    mapping_version BIGINT,
    applied_auth_epoch BIGINT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('OFFBOARDED', 'REVIEW', 'UNMAPPED', 'IGNORED', 'FAILED')),
    error_code VARCHAR(64),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, employee_id),
    CHECK (mapping_version IS NULL OR mapping_version >= 0),
    CHECK (applied_auth_epoch IS NULL OR applied_auth_epoch > 0)
);
CREATE INDEX sys_identity_lifecycle_item_status ON sys_identity_lifecycle_item (status, event_id);
CREATE INDEX sys_identity_lifecycle_item_employee ON sys_identity_lifecycle_item (employee_id, event_id);
CREATE INDEX sys_identity_lifecycle_item_user ON sys_identity_lifecycle_item (user_id, event_id DESC);
CREATE INDEX sys_identity_lifecycle_item_retry ON sys_identity_lifecycle_item (next_attempt_at, event_id)
    WHERE status = 'FAILED';

ALTER TABLE sys_identity_lifecycle
    ADD CONSTRAINT fk_identity_lifecycle_last_event
    FOREIGN KEY (last_event_id) REFERENCES sys_identity_lifecycle_event (id);

-- 撤销上限属于原认证代次，迟到清理不能注销复职后的新登录。
CREATE TABLE sys_identity_session_cleanup
(
    user_id BIGINT NOT NULL REFERENCES sys_user (id),
    revoked_through_epoch BIGINT NOT NULL CHECK (revoked_through_epoch > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    lease_owner UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, revoked_through_epoch),
    CHECK ((lease_until IS NULL) = (lease_owner IS NULL)),
    CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
);
CREATE INDEX sys_identity_session_cleanup_pending
    ON sys_identity_session_cleanup (next_attempt_at, user_id) WHERE status = 'PENDING';
