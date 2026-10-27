-- =====================================================================
-- V1: 核心权限模型与文档骨架
-- 设计依据：《架构决策记录.md》第 4 章（权限模型已定型）
--   · 知识空间为主 + 文档例外
--   · 部门层级树 + 角色
--   · 文档密级（与 ACL 是"与"关系）
--   · 首期不做对外分享外链、不做显式拒绝（deny）
-- =====================================================================

CREATE
EXTENSION IF NOT EXISTS pg_search CASCADE;   -- CASCADE 会一并创建 pgvector

-- ---------------------------------------------------------------------
-- 主体侧：部门树 / 用户 / 角色
-- ---------------------------------------------------------------------

-- 部门层级树，使用物化路径便于一次查出祖先链
CREATE TABLE sys_dept
(
    id         BIGSERIAL PRIMARY KEY,
    parent_id  BIGINT REFERENCES sys_dept (id),
    name       TEXT        NOT NULL,
    -- 物化路径，形如 '/1/4/9/'，始终以 / 开头结尾，便于 LIKE '/1/%' 查子树
    path       TEXT        NOT NULL,
    sort_no    INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dept_parent ON sys_dept (parent_id);
CREATE INDEX idx_dept_path ON sys_dept (path text_pattern_ops);

COMMENT
ON COLUMN sys_dept.path IS '物化路径 /1/4/9/，授权某部门时其整棵子树的用户命中';

-- 密级字典：数值越大越机密。用户 clearance_level 必须 >= 文档 security_level 才可见。
-- 0=公开 1=内部 2=保密 3=机密（分几级、如何对应待业务确认，见决策记录第 10 节"仍待收集"第 5 项）
CREATE TABLE sys_security_level
(
    level SMALLINT PRIMARY KEY,
    name  TEXT NOT NULL
);
INSERT INTO sys_security_level (level, name)
VALUES (0, '公开'),
       (1, '内部'),
       (2, '保密'),
       (3, '机密');

CREATE TABLE sys_user
(
    id              BIGSERIAL PRIMARY KEY,
    username        TEXT        NOT NULL UNIQUE,
    display_name    TEXT        NOT NULL,
    dept_id         BIGINT REFERENCES sys_dept (id),
    -- 用户可见的最高密级
    clearance_level SMALLINT    NOT NULL DEFAULT 0
        REFERENCES sys_security_level (level),
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_dept ON sys_user (dept_id);

CREATE TABLE sys_role
(
    id         BIGSERIAL PRIMARY KEY,
    code       TEXT        NOT NULL UNIQUE,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sys_user_role
(
    user_id BIGINT NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES sys_role (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_role_role ON sys_user_role (role_id);

-- ---------------------------------------------------------------------
-- 客体侧：知识空间（主授权单元）/ 文档 / 版本
-- ---------------------------------------------------------------------

CREATE TABLE kb_space
(
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    description TEXT,
    archived    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 主体类型：1=用户 2=角色 3=部门。与 PrincipalType.java 保持一致。
-- 不做 deny：本表只表达"授予"，不存在拒绝记录。
CREATE TABLE kb_space_acl
(
    id             BIGSERIAL PRIMARY KEY,
    space_id       BIGINT      NOT NULL REFERENCES kb_space (id) ON DELETE CASCADE,
    principal_type SMALLINT    NOT NULL CHECK (principal_type IN (1, 2, 3)),
    principal_id   BIGINT      NOT NULL,
    permission     TEXT        NOT NULL
        CHECK (permission IN ('view', 'download', 'comment', 'edit', 'review', 'manage')),
    -- 主体复合键 '3:2'，把 (类型,ID) 压成单列，使检索侧只需传一个 text[] 参数，
    -- 索引与 SQL 都更简单。由 DB 生成，不可能与上面两列不一致。
    principal_key  TEXT GENERATED ALWAYS AS (principal_type || ':' || principal_id) STORED,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (space_id, principal_type, principal_id, permission)
);
-- 用于"由主体反查可见空间"（PermissionService 预计算 visibleSpaceIds 的主路径）
CREATE INDEX idx_space_acl_principal ON kb_space_acl (principal_key, permission);

CREATE TABLE kb_document
(
    id                 BIGSERIAL PRIMARY KEY,
    space_id           BIGINT      NOT NULL REFERENCES kb_space (id),
    title              TEXT        NOT NULL,
    security_level     SMALLINT    NOT NULL DEFAULT 0
        REFERENCES sys_security_level (level),
    -- draft=草稿 in_review=审核中 published=已发布 archived=归档 deleted=已删
    -- 仅 published 参与检索
    status             TEXT        NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'in_review', 'published', 'archived', 'deleted')),
    owner_id           BIGINT REFERENCES sys_user (id),
    -- 当前对外可见的版本；解析完成后原子切换（见决策记录第 7 章）
    current_version_id BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_doc_space ON kb_document (space_id, status);
CREATE INDEX idx_doc_owner ON kb_document (owner_id);

CREATE TABLE kb_document_version
(
    id           BIGSERIAL PRIMARY KEY,
    doc_id       BIGINT      NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    version_no   INT         NOT NULL,
    -- 原件在对象存储中的 key；原件权威数据在 S3，不在 PG
    object_key   TEXT        NOT NULL,
    file_name    TEXT        NOT NULL,
    file_size    BIGINT      NOT NULL,
    content_hash TEXT        NOT NULL, -- 用于秒传去重
    mime_type    TEXT,
    -- pending=待解析 parsing=解析中 indexed=已建索引 failed=失败
    index_status TEXT        NOT NULL DEFAULT 'pending'
        CHECK (index_status IN ('pending', 'parsing', 'indexed', 'failed')),
    index_error  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (doc_id, version_no)
);
CREATE INDEX idx_docver_status ON kb_document_version (index_status);
CREATE INDEX idx_docver_hash ON kb_document_version (content_hash);

ALTER TABLE kb_document
    ADD CONSTRAINT fk_doc_current_version
        FOREIGN KEY (current_version_id) REFERENCES kb_document_version (id);

-- 文档级例外授权。
-- 【首期语义边界】只能"扩权"：把个别文档额外授予空间外的主体。
-- 无法"收紧"（空间可见但某文档更严）——收紧需要 deny 语义，列入二期。
CREATE TABLE kb_document_acl
(
    id             BIGSERIAL PRIMARY KEY,
    doc_id         BIGINT      NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    principal_type SMALLINT    NOT NULL CHECK (principal_type IN (1, 2, 3)),
    principal_id   BIGINT      NOT NULL,
    permission     TEXT        NOT NULL
        CHECK (permission IN ('view', 'download', 'comment', 'edit', 'review', 'manage')),
    principal_key  TEXT GENERATED ALWAYS AS (principal_type || ':' || principal_id) STORED,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (doc_id, principal_type, principal_id, permission)
);
-- 检索热路径：先按 doc_id 定位，再匹配主体复合键
CREATE INDEX idx_doc_acl_doc ON kb_document_acl (doc_id, permission, principal_key);
CREATE INDEX idx_doc_acl_principal ON kb_document_acl (principal_key, permission);
