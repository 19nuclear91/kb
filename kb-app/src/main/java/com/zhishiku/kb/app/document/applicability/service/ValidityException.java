package com.zhishiku.kb.app.document.applicability.service;

public class ValidityException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(400), UNAUTHORIZED(401), FORBIDDEN(403), PASSWORD_CHANGE_REQUIRED(403), NOT_FOUND(404), PAYLOAD_TOO_LARGE(413),
        REVIEW_IN_PROGRESS(409), REASON_REQUIRED(409), REQUEST_MISMATCH(409), NO_CHANGE(409),
        APPLICABILITY_VERSION_CONFLICT(409), STEWARDSHIP_VERSION_CONFLICT(409), STEWARD_INELIGIBLE(409),
        SPACE_ARCHIVED(409), DOCUMENT_ARCHIVED(409), UNAVAILABLE(503);

        private final int status;

        Reason(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Reason reason;

    public ValidityException(Reason reason) {
        super(switch (reason) {
            case INVALID_INPUT -> "请求参数无效";
            case UNAUTHORIZED -> "登录状态无效";
            case FORBIDDEN -> "当前无法执行此操作";
            case PASSWORD_CHANGE_REQUIRED -> "必须先完成密码修改";
            case NOT_FOUND -> "文档或版本不存在，或当前不可访问";
            case PAYLOAD_TOO_LARGE -> "请求体超过限制";
            case UNAVAILABLE -> "暂时无法确认适用期或复审信息";
            default -> "当前状态已变化，请重新读取后操作";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
