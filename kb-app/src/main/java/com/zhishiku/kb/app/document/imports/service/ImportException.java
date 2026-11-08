package com.zhishiku.kb.app.document.imports.service;

/** 导入错误只有固定代码，禁止携带请求值和底层异常。 */
public final class ImportException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(400), FORBIDDEN(403), NOT_FOUND(404), CONFLICT(409),
        PAYLOAD_TOO_LARGE(413), INVALID_FILE(415), CAPACITY(429), UNAVAILABLE(503);

        private final int status;

        Reason(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Reason reason;

    public ImportException(Reason reason) {
        super("IMPORT_" + reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
