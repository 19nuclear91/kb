-- 旧事件没有提交观测，保持 NULL；不能以迁移时间伪造可靠接收时间。
ALTER TABLE sys_identity_lifecycle_event
    ADD COLUMN commit_before TIMESTAMPTZ,
    ADD COLUMN commit_after TIMESTAMPTZ;
ALTER TABLE sys_identity_lifecycle_event ALTER COLUMN commit_before SET DEFAULT clock_timestamp();

-- 原认证代次只关联第一次真正推进该代次的限制，复职和重投不得改写。
CREATE TABLE sys_identity_revocation_evidence
(
    user_id BIGINT NOT NULL REFERENCES sys_user (id),
    revoked_epoch BIGINT NOT NULL CHECK (revoked_epoch > 0),
    event_id BIGINT NOT NULL REFERENCES sys_identity_lifecycle_event (id),
    initial_reception BOOLEAN NOT NULL,
    barrier_before TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    barrier_after TIMESTAMPTZ,
    PRIMARY KEY (user_id, revoked_epoch)
);
CREATE INDEX sys_identity_revocation_event ON sys_identity_revocation_evidence (event_id);

-- 仅保存内部关联键和单调时差；不保存 token、员工标识或模型内容。
CREATE TABLE sys_identity_output_stop
(
    execution_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user (id),
    auth_epoch BIGINT NOT NULL CHECK (auth_epoch > 0),
    checked_at TIMESTAMPTZ NOT NULL,
    after_check_min_nanos BIGINT NOT NULL CHECK (after_check_min_nanos >= 0),
    after_check_max_nanos BIGINT NOT NULL CHECK (after_check_max_nanos >= after_check_min_nanos
        AND after_check_max_nanos <= 604800000000000
        AND after_check_max_nanos - after_check_min_nanos <= 2000000000),
    reason VARCHAR(32) NOT NULL CHECK (reason IN ('ACCOUNT_REVOKED', 'AUTHENTICATION_UNAVAILABLE', 'OUTPUT_PERMIT_EXPIRED')),
    binding_digest CHAR(64) NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX sys_identity_output_stop_recent ON sys_identity_output_stop (reported_at DESC);

-- 保留可复核的原始界限，应用用同一领域计算器判定耗时区间和证据质量。
CREATE VIEW sys_identity_output_stop_evidence AS
SELECT s.*, r.event_id, r.initial_reception, r.barrier_before, r.barrier_after, e.commit_before, e.commit_after
  FROM sys_identity_output_stop s
  LEFT JOIN sys_identity_revocation_evidence r ON r.user_id = s.user_id AND r.revoked_epoch = s.auth_epoch
  LEFT JOIN sys_identity_lifecycle_event e ON e.id = r.event_id;
