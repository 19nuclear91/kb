package com.zhishiku.kb.eval.collector;

/** 诊断只携带受控位置，原始异常与输入值不进入异常链。 */
public final class CollectionException extends RuntimeException {
    public enum Code {
        ARGUMENT(2), INPUT(2), LIMIT(2), CONFIG(2), CREDENTIAL(2), OUTPUT(2), LOCAL(2),
        IDENTITY(3), HTTP(3), PROTOCOL(3), TIMEOUT(3), INTERRUPTED(3), TRANSPORT(3);

        private final int exitCode;

        Code(int exitCode) {
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    private final Code code;

    public CollectionException(Code code, String role, int line, String field) {
        super("COLLECT_ERROR " + code.name() + " role=" + safe(role)
                + " line=" + Math.max(0, line) + " field=" + safe(field), null, false, false);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    private static String safe(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9.]{0,63}")) {
            return "unknown";
        }
        return value;
    }
}
