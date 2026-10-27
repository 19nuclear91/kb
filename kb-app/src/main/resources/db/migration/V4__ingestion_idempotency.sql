-- =====================================================================
-- V4: 文档摄取幂等约束
--
-- 一个版本只能有一个持久解析任务。Worker 可能因租约超时重复领取同一任务，
-- 但业务层不能因为重复投递而产生多个并行任务。
-- =====================================================================

CREATE UNIQUE INDEX uq_parse_task_version ON kb_parse_task (version_id);
CREATE INDEX idx_document_version_object_key ON kb_document_version (object_key);
