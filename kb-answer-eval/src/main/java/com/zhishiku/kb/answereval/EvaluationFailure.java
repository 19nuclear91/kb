package com.zhishiku.kb.answereval;

/** 仅输出固定错误类别，不保留输入、路径或远程异常正文。 */
public final class EvaluationFailure extends RuntimeException {
    public enum Code {
        ARGUMENT, INPUT, LIMIT, PATH, DIGEST, SOURCE, BINDING, CONFIGURATION,
        OUTPUT, PROTOCOL, IDENTITY, DEADLINE, SUBMISSION_UNKNOWN, CANCELLED, INCOMPLETE, REVIEW_CONFLICT
    }

    private final Code code;

    public EvaluationFailure(Code code) {
        super("ANSWER_EVAL_ERROR " + code.name(), null, false, false);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
