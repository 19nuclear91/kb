package com.zhishiku.kb.app.auth.service.diagnostics;

/** 固定安全类别，不保存请求值、身份细节或底层异常。 */
public final class DiagnosticException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(400, "诊断请求参数无效"),
        DIAGNOSTIC_LIMIT_EXCEEDED(400, "诊断范围超过限制"),
        PAYLOAD_TOO_LARGE(413, "诊断请求体过大"),
        UNAUTHORIZED(401, "登录状态无效"),
        FORBIDDEN(403, "当前无诊断操作资格"),
        NOT_FOUND(404, "目标不存在或不可管理"),
        DIAGNOSTIC_UNAVAILABLE(503, "诊断暂不可用");

        private final int status;
        private final String message;

        Reason(int status, String message) {
            this.status = status;
            this.message = message;
        }

        public int status() {
            return status;
        }
    }

    private final Reason reason;

    public DiagnosticException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
