package com.zhishiku.kb.app.service.answercard;

/** 只携带固定错误类别，不记录用户问题、答案、来源或原始异常。 */
public final class AnswerCardFailure extends RuntimeException {
    private final int status;
    private final String reason;

    public AnswerCardFailure(int status, String reason) {
        super("标准答案请求未完成");
        this.status = status;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public static AnswerCardFailure invalid() {
        return new AnswerCardFailure(400, "INVALID_INPUT");
    }

    public static AnswerCardFailure hidden() {
        return new AnswerCardFailure(404, "NOT_FOUND");
    }

    public static AnswerCardFailure forbidden() {
        return new AnswerCardFailure(403, "FORBIDDEN");
    }

    public static AnswerCardFailure conflict(String reason) {
        return new AnswerCardFailure(409, reason);
    }

    public static AnswerCardFailure unavailable() {
        return new AnswerCardFailure(503, "UNAVAILABLE");
    }
}
