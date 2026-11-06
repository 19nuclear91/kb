package com.zhishiku.kb.app.auth.lifecycle;

/** 输入拒绝只携带固定类别，不能携带来源载荷或标识。 */
final class DingTalkInputRejected extends RuntimeException {
    enum Reason {
        SCOPE_MISMATCH, INVALID_EVENT, UNSUPPORTED_EVENT, MALFORMED_MESSAGE,
        MESSAGE_LIMIT, EMPLOYEE_LIMIT, UNSUPPORTED_COMMAND, QUEUE_FULL
    }

    private final Reason reason;

    DingTalkInputRejected(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
