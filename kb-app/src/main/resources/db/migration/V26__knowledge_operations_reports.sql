-- 只在首次升级绑定当时的管理员；后续启动与正式引导不修复既有角色撤权。
-- 新 code 入库前必须排空旧 app，禁止与不认识此目录的二进制混部或直接回退。
INSERT INTO sys_role_permission (role_id, permission)
SELECT id, 'operations:reports:read' FROM sys_role WHERE code = 'ROLE_ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;

-- 复用既有文档状态、版本 doc_id 索引，仅补报表所需的事实与活动审核索引。
CREATE INDEX idx_collaboration_kind_time ON kb_collaboration_event (kind, occurred_at);
CREATE INDEX idx_review_active_document ON kb_document_review (doc_id) WHERE state = 'IN_REVIEW';
