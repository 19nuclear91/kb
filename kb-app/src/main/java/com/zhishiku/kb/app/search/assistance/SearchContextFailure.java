package com.zhishiku.kb.app.search.assistance;

/** 凭据层只携带固定失败种类，由搜索或管理入口映射到各自 HTTP 契约。 */
public final class SearchContextFailure extends RuntimeException {
    public enum Kind {
        INVALID_INPUT(400), ASSISTANCE_STALE(409), DICTIONARY_CHANGED(409), ASSISTANCE_UNAVAILABLE(503);

        private final int status;

        Kind(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Kind kind;

    public SearchContextFailure(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return kind.status();
    }
}
