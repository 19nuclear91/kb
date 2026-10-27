-- 私人收藏仅保存文档身份；读取时仍按当前文档权限过滤。
CREATE TABLE kb_document_favorite
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    document_id BIGINT      NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, document_id)
);

CREATE INDEX idx_document_favorite_user_page ON kb_document_favorite (user_id, id DESC);
CREATE INDEX idx_document_favorite_document ON kb_document_favorite (document_id);
