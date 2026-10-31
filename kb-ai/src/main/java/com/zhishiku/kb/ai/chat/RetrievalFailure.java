package com.zhishiku.kb.ai.chat;

/** 检索失败不携带查询、凭据或下游消息。 */
public final class RetrievalFailure extends RuntimeException {
    public static final String CODE = "SEARCH_UNAVAILABLE";
    public static final String MESSAGE = "检索暂不可用，请稍后重试。";

    public enum Kind {
        HTTP, PROTOCOL, PAYLOAD_LIMIT, TIMEOUT, DEPENDENCY
    }

    private final Kind kind;

    public RetrievalFailure(Kind kind) {
        super(CODE);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
