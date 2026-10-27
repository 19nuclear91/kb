-- =====================================================================
-- V2: 检索分块表 + 双索引（ParadeDB 全文 / pgvector 向量）
--
-- 【语法已核实】依据 ParadeDB 官方文档源码（github.com/paradedb/paradedb/docs）：
--   · 0.25.0 起索引访问方法更名为 USING paradedb，USING bm25 保留为兼容别名
--   · 分词器通过列强制类型转换配置：(content::pdb.jieba)
--   · BM25 评分函数为 pdb.score(<key_field>)，不是 paradedb.score()
--   · 匹配算子：||| 任一词命中，&&& 全部词命中，@@@ 通用/查询解析器
-- 部署前请用 db/verify_paradedb.sql 对实际安装版本复验一次。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 分块表
-- space_id 与 security_level 为冗余列：检索时直接过滤，避免 JOIN 文档表。
-- 冗余列由应用在写入分块时填充，并在文档移动空间/改密级时同步更新（见 V2 末尾触发器）。
-- ---------------------------------------------------------------------
CREATE TABLE kb_doc_chunk
(
    id             BIGSERIAL PRIMARY KEY,
    doc_id         BIGINT      NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    version_id     BIGINT      NOT NULL REFERENCES kb_document_version (id) ON DELETE CASCADE,

    -- ↓ 冗余自 kb_document，仅为检索过滤
    space_id       BIGINT      NOT NULL,
    security_level SMALLINT    NOT NULL,

    chunk_idx      INT         NOT NULL,
    content        TEXT        NOT NULL,
    -- 来源定位，用于引用溯源
    page_no        INT,
    heading_path   TEXT,

    -- 维度需与嵌入模型一致。1024 对应通义 text-embedding-v3 默认维度。
    -- 换模型通常需要重建全部向量，并管理模型版本（见决策记录 3.1）。
    embedding      VECTOR(1024),
    embed_model    TEXT,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (version_id, chunk_idx)
);

CREATE INDEX idx_chunk_doc ON kb_doc_chunk (doc_id);
CREATE INDEX idx_chunk_version ON kb_doc_chunk (version_id);

-- ---------------------------------------------------------------------
-- 索引一：ParadeDB 全文索引（BM25）
--
-- content 使用 jieba 分词器 —— ParadeDB 内置，基于词典 + 统计模型，
-- 对中文歧义边界的处理优于 chinese_compatible（后者按单字切分）。
--
-- jieba 默认开启 search_mode：'南京市长江大桥' 同时产出
-- {南京,京市,南京市,长江,大桥,长江大桥}，利于召回。
-- 【注意】查询侧建议用 search_mode=false 只保留完整词，避免命中仅含部分复合词的文档。
--         该差异已在 SearchMapper 的查询 SQL 中体现。
--
-- space_id / security_level / status 一并进索引，使权限过滤能下推到索引内部，
-- 而不是先召回再过滤。
-- ---------------------------------------------------------------------
CREATE INDEX idx_chunk_fts ON kb_doc_chunk
    USING paradedb (
    id,
    (content::pdb.jieba),
    space_id,
    security_level,
    doc_id
    )
    WITH (key_field = 'id');

-- ---------------------------------------------------------------------
-- 索引二：pgvector HNSW 向量索引
--
-- 选型说明：ParadeDB 0.25.0+ 支持把向量并入同一个 paradedb 索引
-- （见文末"可选升级路径"），能显著改善强过滤场景的召回，但该能力标记为 beta。
-- P0 先用 pgvector 稳定的 HNSW；待 ParadeDB 向量能力转正或实测通过后再切换。
--
-- 余弦距离对应 <=> 算子。ef_construction / m 需按实际数据量调优。
-- ---------------------------------------------------------------------
CREATE INDEX idx_chunk_vec ON kb_doc_chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ---------------------------------------------------------------------
-- 冗余列同步：文档换空间或改密级时，同步已有分块
-- 这是"冗余换检索性能"的必要代价，必须保证不漂移。
-- ---------------------------------------------------------------------
CREATE
OR REPLACE FUNCTION sync_chunk_denorm() RETURNS TRIGGER AS $$
BEGIN
    IF
(NEW.space_id IS DISTINCT FROM OLD.space_id)
       OR (NEW.security_level IS DISTINCT FROM OLD.security_level) THEN
UPDATE kb_doc_chunk
SET space_id       = NEW.space_id,
    security_level = NEW.security_level
WHERE doc_id = NEW.id;
END IF;
RETURN NEW;
END;
$$
LANGUAGE plpgsql;

CREATE TRIGGER trg_sync_chunk_denorm
    AFTER UPDATE
    ON kb_document
    FOR EACH ROW EXECUTE FUNCTION sync_chunk_denorm();

-- ---------------------------------------------------------------------
-- 解析任务队列（PG 持久任务队列方案）
-- 决策记录第 7 章"队列二选一"尚未定案；此处先落 PG 方案，
-- 好处是任务与文档版本可在同一事务提交，无需 Outbox。
-- 若改用 RabbitMQ，删除本表并改为 Outbox 投递即可，业务代码接口不变。
-- ---------------------------------------------------------------------
CREATE TABLE kb_parse_task
(
    id           BIGSERIAL PRIMARY KEY,
    version_id   BIGINT      NOT NULL REFERENCES kb_document_version (id) ON DELETE CASCADE,
    status       TEXT        NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'running', 'succeeded', 'failed')),
    attempts     INT         NOT NULL DEFAULT 0,
    max_attempts INT         NOT NULL DEFAULT 3,
    -- 租约：worker 领取后写入，超时未完成则可被回收重投
    lease_until  TIMESTAMPTZ,
    lease_owner  TEXT,
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 领取任务的扫描路径：status + 到期租约
CREATE INDEX idx_task_claim ON kb_parse_task (status, lease_until);

-- =====================================================================
-- 可选升级路径（暂不启用）：向量并入 ParadeDB 统一索引
--
-- ParadeDB 0.25.0+ 可把 vector 列放进同一个 paradedb 索引，让全文、向量、
-- 过滤条件在一个索引内协同，对"权限强过滤"这类选择性查询的召回与延迟有明显好处
-- —— 正是本系统的典型场景（见决策记录 3.4 的 HNSW 过滤召回风险）。
-- 该能力目前标记为 beta，故 P0 不启用。实测通过后可替换为：
--
--   DROP INDEX idx_chunk_fts;
--   DROP INDEX idx_chunk_vec;
--   CREATE INDEX idx_chunk_unified ON kb_doc_chunk
--   USING paradedb (
--       id, (content::pdb.jieba), space_id, security_level, doc_id,
--       embedding vector_cosine_ops
--   ) WITH (key_field = 'id');
--
-- 注意：ParadeDB 使用 pgvector 的 vector 类型，但不使用 pgvector 的 HNSW/IVF 索引。
-- =====================================================================
