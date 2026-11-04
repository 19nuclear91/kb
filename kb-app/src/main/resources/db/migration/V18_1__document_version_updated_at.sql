-- V5 的 trg_document_version_updated_at 写入 NEW.updated_at，但 V1 建表时没有该列，
-- 任何 UPDATE kb_document_version 都会失败；应用也读取 v.updated_at。
-- 版本号排在 V19 之前：dev 种子在 V3 已插入版本行，V23 的 UPDATE 必须在补列之后执行。
ALTER TABLE kb_document_version
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
