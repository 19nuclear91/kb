package com.zhishiku.kb.app.auth.lifecycle;

import java.time.Instant;

public record LifecycleItem(long id, long eventId, String employeeId, Long userId, Long mappingVersion,
                            Long appliedAuthEpoch, LifecycleItemStatus status, String errorCode, int attempts,
                            Instant nextAttemptAt, Instant createdAt, Instant updatedAt, Instant resolvedAt) {
    @Override
    public String toString() {
        return "LifecycleItem[status=" + status + "]";
    }
}
