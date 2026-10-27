-- =====================================================================
-- V3: 开发种子数据
--
-- 目的不是"造点数据看看"，而是构造一组能验证权限边界的场景：
-- 每条检索断言都应有明确的预期结果。见 db/verify_permission.sql。
--
-- 生产环境不要执行本迁移（Flyway 可用 spring.flyway.locations 区分环境）。
-- =====================================================================

-- 部门树：
--   总公司(1)
--     ├── 研发中心(2)
--     │     ├── 平台组(4)
--     │     └── 算法组(5)
--     └── 财务部(3)
INSERT INTO sys_dept (id, parent_id, name, path)
VALUES (1, NULL, '总公司', '/1/'),
       (2, 1, '研发中心', '/1/2/'),
       (3, 1, '财务部', '/1/3/'),
       (4, 2, '平台组', '/1/2/4/'),
       (5, 2, '算法组', '/1/2/5/');
SELECT setval('sys_dept_id_seq', 5);

INSERT INTO sys_role (id, code, name)
VALUES (1, 'ROLE_ADMIN', '系统管理员'),
       (2, 'ROLE_AUDITOR', '审计员');
SELECT setval('sys_role_id_seq', 2);

-- 用户：注意 clearance_level 的差异，用于验证密级过滤
INSERT INTO sys_user (id, username, display_name, dept_id, clearance_level)
VALUES (1, 'alice', '爱丽丝（平台组，保密级）', 4, 2),
       (2, 'bob', '鲍勃（算法组，内部级）', 5, 1),
       (3, 'carol', '卡罗尔（财务部，机密级）', 3, 3),
       (4, 'dave', '戴夫（总公司，公开级）', 1, 0);
SELECT setval('sys_user_id_seq', 4);

-- alice 是本地管理操作者；carol 仍是只做审计的用户。
INSERT INTO sys_user_role (user_id, role_id)
VALUES (1, 1),
       (3, 2);

-- 知识空间
INSERT INTO kb_space (id, code, name)
VALUES (1, 'TECH', '技术文档空间'),
       (2, 'FINANCE', '财务文档空间');
SELECT setval('kb_space_id_seq', 2);

-- 空间授权：
--   技术空间 → 授予"研发中心"(2)，其子树（平台组4、算法组5）的用户全部命中
--   财务空间 → 授予"财务部"(3)
INSERT INTO kb_space_acl (space_id, principal_type, principal_id, permission)
VALUES (1, 3, 2, 'view'), -- 3=部门, 研发中心
       (1, 3, 2, 'download'),
       (2, 3, 3, 'view'), -- 财务部
       (2, 2, 2, 'view');
-- 2=角色, 审计员也可看财务空间

-- 文档：密级递增，用于验证 clearance 过滤
INSERT INTO kb_document (id, space_id, title, security_level, status, owner_id)
VALUES (1, 1, '微服务拆分指南', 0, 'published', 1),
       (2, 1, '内部架构评审纪要', 1, 'published', 1),
       (3, 1, '核心算法设计说明', 2, 'published', 2),
       (4, 2, '年度预算明细', 3, 'published', 3),
       (5, 1, '尚未发布的草稿', 0, 'draft', 1);
SELECT setval('kb_document_id_seq', 5);

-- 文档级例外：把财务空间的《年度预算明细》额外授予 alice（她本不在财务部）
-- 用于验证"文档例外可扩权"这条语义
INSERT INTO kb_document_acl (doc_id, principal_type, principal_id, permission)
VALUES (4, 1, 1, 'view');
-- 1=用户, alice

-- 版本与分块（embedding 置空，由 Worker 回填；此处只验证全文与权限链路）
INSERT INTO kb_document_version (id, doc_id, version_no, object_key, file_name, file_size, content_hash, index_status)
VALUES (1, 1, 1, 'dev/1.md', '微服务拆分指南.md', 1024, 'hash1', 'indexed'),
       (2, 2, 1, 'dev/2.md', '内部架构评审纪要.md', 2048, 'hash2', 'indexed'),
       (3, 3, 1, 'dev/3.md', '核心算法设计说明.md', 4096, 'hash3', 'indexed'),
       (4, 4, 1, 'dev/4.md', '年度预算明细.xlsx', 8192, 'hash4', 'indexed'),
       (5, 5, 1, 'dev/5.md', '尚未发布的草稿.md', 512, 'hash5', 'indexed');
SELECT setval('kb_document_version_id_seq', 5);

UPDATE kb_document d
SET current_version_id = v.id FROM kb_document_version v
WHERE v.doc_id = d.id;

INSERT INTO kb_doc_chunk (doc_id, version_id, space_id, security_level, chunk_idx, content, page_no)
VALUES (1, 1, 1, 0, 0, '微服务拆分应当沿业务边界进行，避免按技术分层拆分。模块化单体是一种稳妥的起步方式。', 1),
       (1, 1, 1, 0, 1, '服务间通信优先选择同步接口调用，跨服务分布式事务应尽量避免。', 2),
       (2, 2, 1, 1, 0, '本次架构评审确认采用 PostgreSQL 统一承载业务数据与检索，不再引入独立搜索引擎。', 1),
       (3, 3, 1, 2, 0, '核心算法采用混合召回策略，关键词检索与向量检索并行执行后做排序融合。', 1),
       (4, 4, 2, 3, 0, '年度预算中研发投入占比与上年持平，财务部门需要逐项复核明细科目。', 1),
       (5, 5, 1, 0, 0, '这是一篇尚未发布的草稿，不应出现在任何检索结果中。', 1);
