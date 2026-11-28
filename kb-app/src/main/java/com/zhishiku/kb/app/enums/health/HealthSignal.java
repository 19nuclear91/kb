package com.zhishiku.kb.app.enums.health;

/** 健康诊断的六类维护信号，声明顺序同时用于响应展示。 */
public enum HealthSignal {
    STEWARD_UNASSIGNED,
    STEWARD_NEEDS_REASSIGN,
    REVIEW_OVERDUE,
    REVIEW_DUE_SOON,
    APPLICABILITY_EXPIRED,
    APPLICABILITY_EXPIRING_SOON
}
