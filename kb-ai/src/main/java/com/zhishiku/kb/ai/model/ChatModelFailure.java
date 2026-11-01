package com.zhishiku.kb.ai.model;

/** 只保留固定类别，不保留可能包含秘密的上游异常或载荷。 */
public final class ChatModelFailure extends RuntimeException {
    public enum Kind {
        HTTP_REJECTED, CONNECT_FAILED, TIMEOUT, PROTOCOL_INVALID, INCOMPLETE_STREAM,
        OUTPUT_TRUNCATED, UNSUPPORTED_OUTPUT, PAYLOAD_LIMIT, BUDGET_UNAVAILABLE, ALREADY_SUBSCRIBED
    }

    private final Kind kind;

    public ChatModelFailure(Kind kind) {
        super(kind.name(), null, false, true);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
