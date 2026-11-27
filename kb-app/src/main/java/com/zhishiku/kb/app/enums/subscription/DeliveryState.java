package com.zhishiku.kb.app.enums.subscription;

/** 提醒状态：待处理、已生成通知、没有合格项目。终态不可重开。 */
public enum DeliveryState {
    PENDING,
    NOTIFIED,
    NO_ELIGIBLE
}
