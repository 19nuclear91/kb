package com.zhishiku.kb.app.search.execution;

/** 可选阶段的固定失败分类，不携带上游错误文本。 */
public final class SearchStageFailure extends RuntimeException {
    public enum Reason {
        TIME_BUDGET, TIMEOUT, CAPACITY, UPSTREAM, INVALID_RESPONSE, PAYLOAD_LIMIT, PROFILE_UNAVAILABLE
    }

    private final Reason reason;

    public SearchStageFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
