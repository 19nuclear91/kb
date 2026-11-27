package com.zhishiku.kb.app.service.subscription;

/** 对外仅投影固定原因码，不包含目标、输入或底层异常。 */
public final class SubscriptionFailure extends RuntimeException {
    private final int status;
    private final String reason;

    public SubscriptionFailure(int status, String reason) {
        super(reason);
        this.status = status;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public static SubscriptionFailure invalid() {
        return new SubscriptionFailure(400, "INVALID_INPUT");
    }

    public static SubscriptionFailure hidden() {
        return new SubscriptionFailure(404, "NOT_FOUND");
    }

    public static SubscriptionFailure conflict(String reason) {
        return new SubscriptionFailure(409, reason);
    }

    public static SubscriptionFailure unavailable() {
        return new SubscriptionFailure(503, "UNAVAILABLE");
    }
}
