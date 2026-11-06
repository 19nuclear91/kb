package com.zhishiku.kb.app.auth.lifecycle;

/** 事件不删除，新增事件及单向冲突标记与映射版本共同约束复职证据。 */
public record LifecycleEventBoundary(long lastEventId, long eventCount, long conflicts, long pending, long review) {
}
