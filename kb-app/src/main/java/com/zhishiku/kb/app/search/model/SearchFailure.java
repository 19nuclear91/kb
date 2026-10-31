package com.zhishiku.kb.app.search.model;

/** 搜索的固定失败类别；消息中不接受用户或上游内容。 */
public final class SearchFailure extends RuntimeException {
    public enum Kind {
        INVALID_INPUT, REQUEST_TOO_LARGE, USER_LIMIT, CAPACITY, TIMEOUT, CANCELLED, DEPENDENCY, RESPONSE_TOO_LARGE
    }

    private final Kind kind;
    private final int retryAfter;

    public SearchFailure(Kind kind) {
        this(kind, 0);
    }

    public SearchFailure(Kind kind, int retryAfter) {
        super(kind.name());
        this.kind = kind;
        this.retryAfter = Math.max(0, retryAfter);
    }

    public Kind kind() {
        return kind;
    }

    public int retryAfter() {
        return retryAfter;
    }

    public int status() {
        return switch (kind) {
            case INVALID_INPUT -> 400;
            case REQUEST_TOO_LARGE -> 413;
            case USER_LIMIT -> 429;
            default -> 503;
        };
    }

    public String safeMessage() {
        return switch (kind) {
            case INVALID_INPUT -> "搜索输入不符合要求";
            case REQUEST_TOO_LARGE -> "搜索请求过大";
            case USER_LIMIT -> "搜索请求过于频繁，请稍后重试";
            default -> "检索暂不可用，请稍后重试";
        };
    }
}
