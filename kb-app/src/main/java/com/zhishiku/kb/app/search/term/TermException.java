package com.zhishiku.kb.app.search.term;

/** 词典管理的固定安全失败类别，不携带原始输入或依赖异常。 */
public final class TermException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(400, "词典请求参数无效"), PAYLOAD_TOO_LARGE(413, "词典请求体过大"),
        FORBIDDEN(403, "当前无词典操作资格"), NOT_FOUND(404, "词典记录不存在或不可访问"),
        NAME_CONFLICT(409, "标准名称已被占用"), EXPRESSION_CONFLICT(409, "表达与已有词典关系冲突"),
        TARGET_UNAVAILABLE(409, "目标术语当前不可选"), VERSION_CONFLICT(409, "词条已变化，请重新读取"),
        REQUEST_MISMATCH(409, "请求标识已用于不同意图"), NO_CHANGE(409, "词条没有变化"),
        DICTIONARY_CHANGED(409, "词典已变化，请重新查询"), UNAVAILABLE(503, "词典服务暂不可用，请稍后核对");

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

    public TermException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
