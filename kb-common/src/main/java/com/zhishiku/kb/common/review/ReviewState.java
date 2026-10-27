package com.zhishiku.kb.common.review;

/** 每轮审核的状态；驳回、撤回或取消后须创建新轮次，不能重开旧轮次。 */
public enum ReviewState {
    IN_REVIEW,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    CANCELLED;

    public ReviewState decide(ReviewState decision) {
        if (this != IN_REVIEW || decision == null || decision == IN_REVIEW) {
            throw new IllegalStateException("STATE_CONFLICT");
        }
        return decision;
    }
}
