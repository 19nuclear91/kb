-- 对管理端使用内部不可变游标，不把员工标识作为 URL 或分页令牌。
ALTER TABLE sys_identity_lifecycle_item ADD COLUMN id BIGSERIAL NOT NULL;
ALTER TABLE sys_identity_lifecycle_item ADD COLUMN resolved_at TIMESTAMPTZ;
CREATE UNIQUE INDEX sys_identity_lifecycle_item_id ON sys_identity_lifecycle_item (id);
CREATE INDEX sys_identity_lifecycle_item_event_cursor ON sys_identity_lifecycle_item (event_id, id);
