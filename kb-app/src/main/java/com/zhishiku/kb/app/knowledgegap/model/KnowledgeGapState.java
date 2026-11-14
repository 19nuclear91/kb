package com.zhishiku.kb.app.knowledgegap.model;

/** 方案已提交与本人确认解决是不同事实；三类终态均不可重开。 */
public enum KnowledgeGapState {
    SUBMITTED,
    IN_PROGRESS,
    AWAITING_CONFIRMATION,
    RESOLVED,
    CLOSED,
    WITHDRAWN;

    public boolean terminal() {
        return this == RESOLVED || this == CLOSED || this == WITHDRAWN;
    }
}
