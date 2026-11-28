package com.zhishiku.kb.app.service.health;

/** 健康诊断只传播固定错误类别，不保存输入值或底层异常。 */
public final class HealthFailure extends RuntimeException {
    private final int status;
    private final String reason;

    public HealthFailure(int status, String reason) {
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

    public static HealthFailure invalid() {
        return new HealthFailure(400, "INVALID_INPUT");
    }

    public static HealthFailure unavailable() {
        return new HealthFailure(503, "UNAVAILABLE");
    }
}
