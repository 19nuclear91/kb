-- =====================================================================
-- V7: 统一解析输出与引用溯源元数据
--
-- 新字段全部兼容历史版本。历史数据没有可靠的解析器身份或来源坐标时
-- 保持 NULL/legacy，不通过迁移脚本推断或伪造位置。
-- =====================================================================

ALTER TABLE kb_document_version
    ADD COLUMN parser_name TEXT,
    ADD COLUMN parser_version TEXT,
    ADD COLUMN parse_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN parse_issue_count INT NOT NULL DEFAULT 0;

ALTER TABLE kb_document_version
    ADD CONSTRAINT ck_document_version_parse_issue_count_nonnegative
        CHECK (parse_issue_count >= 0);

CREATE TABLE kb_parse_issue
(
    id         BIGSERIAL PRIMARY KEY,
    version_id BIGINT      NOT NULL REFERENCES kb_document_version (id) ON DELETE CASCADE,
    severity   TEXT        NOT NULL CHECK (severity IN ('WARNING', 'ERROR')),
    code       TEXT        NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 128),
    message    TEXT        NOT NULL CHECK (length(btrim(message)) BETWEEN 1 AND 4096),
    page_no    INT,
    line_start INT,
    line_end   INT,
    char_start INT,
    char_end   INT,
    locator    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_parse_issue_line_range CHECK (
        (line_start IS NULL OR line_start > 0)
            AND (line_end IS NULL OR line_end > 0)
            AND (line_start IS NULL OR line_end IS NULL OR line_end >= line_start)
        ),
    CONSTRAINT ck_parse_issue_char_range CHECK (
        (char_start IS NULL OR char_start >= 0)
            AND (char_end IS NULL OR char_end >= 0)
            AND (char_start IS NULL OR char_end IS NULL OR char_end >= char_start)
        )
);

CREATE INDEX idx_parse_issue_version ON kb_parse_issue (version_id, severity, id);

ALTER TABLE kb_doc_chunk
    ADD COLUMN structure_type TEXT,
    ADD COLUMN source_line_start   INT,
    ADD COLUMN source_line_end     INT,
    ADD COLUMN source_char_start   INT,
    ADD COLUMN source_char_end     INT,
    ADD COLUMN source_locator      TEXT,
    ADD COLUMN structure_metadata  JSONB;

ALTER TABLE kb_doc_chunk
    ADD CONSTRAINT ck_chunk_source_line_range CHECK (
        (source_line_start IS NULL OR source_line_start > 0)
            AND (source_line_end IS NULL OR source_line_end > 0)
            AND (source_line_start IS NULL OR source_line_end IS NULL
            OR source_line_end >= source_line_start)
        ),
    ADD CONSTRAINT ck_chunk_source_char_range CHECK (
        (source_char_start IS NULL OR source_char_start >= 0)
        AND (source_char_end IS NULL OR source_char_end >= 0)
        AND (source_char_start IS NULL OR source_char_end IS NULL
             OR source_char_end >= source_char_start)
    ),
    ADD CONSTRAINT ck_chunk_structure_metadata_object CHECK (
        structure_metadata IS NULL OR jsonb_typeof(structure_metadata) = 'object'
    ),
    ADD CONSTRAINT ck_chunk_structure_metadata_size CHECK (
        structure_metadata IS NULL OR octet_length(structure_metadata::text) <= 65536
    );

CREATE INDEX idx_chunk_source_page ON kb_doc_chunk (page_no, version_id);
