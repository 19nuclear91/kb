package com.zhishiku.kb.eval.input;

/**
 * 只携带受控错误定位，不能附加输入值或原始异常。
 */
public final class EvaluationException extends RuntimeException {
    public enum Code {
        ARGUMENT, LOCAL_FILE_REQUIRED, READ_FAILED, INVALID_JSON, INVALID_FIELD,
        UNSUPPORTED_SCHEMA, DUPLICATE, IDENTITY_MISMATCH, DIGEST_MISMATCH,
        COVERAGE_MISMATCH, EXECUTION_FAILED, INVALID_BASELINE, INVALID_THRESHOLD,
        OUTPUT_EXISTS, OUTPUT_FAILED
    }

    private final Code code;
    private final String role;
    private final int line;
    private final String field;

    public EvaluationException(Code code, String role, int line, String field) {
        super(code.name());
        this.code = code;
        this.role = role;
        this.line = line;
        this.field = field;
    }

    public Code code() {
        return code;
    }

    public String role() {
        return role;
    }

    public int line() {
        return line;
    }

    public String field() {
        return field;
    }
}
