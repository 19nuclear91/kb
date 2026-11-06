package com.zhishiku.kb.app.auth.lifecycle;

/** 处置状态不表示记录可以删除；事件去重与复职边界长期保留。 */
public enum LifecycleItemStatus {
    OFFBOARDED, REVIEW, UNMAPPED, IGNORED, FAILED;

    public boolean unresolved() {
        return this == REVIEW || this == UNMAPPED || this == FAILED;
    }

    public boolean automaticRetry() {
        return this == FAILED;
    }
}
