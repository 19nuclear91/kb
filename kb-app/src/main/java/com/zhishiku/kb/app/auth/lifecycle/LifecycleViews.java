package com.zhishiku.kb.app.auth.lifecycle;

import java.time.Instant;

/** 管理读取专用投影，不含外部企业/员工标识、凭据、载荷或内容摘要。 */
public final class LifecycleViews {
    private LifecycleViews() {
    }

    public record User(long userId, long version, long mappingVersion, boolean manualEnabled, boolean enabled,
                       LifecycleAssignment.Kind kind, LifecycleAssignment.Restriction restriction, String connectionId,
                       Long lastEventId, Instant updatedAt, Instant restoredAt) {
    }

    public record Event(long id, String eventType, boolean conflicted, Instant bornAt, Instant recordedAt,
                        long restricted, long review, long unmapped, long failed) {
    }

    public record Item(long id, Long userId, Long mappingVersion, LifecycleItemStatus status, String errorCode,
                       int attempts, Instant updatedAt, Instant resolvedAt) {
    }

    public record Emergency(long userId, String displayName, long version, long mappingVersion, boolean qualified) {
    }

    public record Counts(long managed, long restricted, long unmapped, long review, long failed) {
    }

    public record Status(boolean subscriptionEnabled, boolean configured, String connectionId, boolean connected,
                         Instant lastConnectedAt, boolean emergencyReady, Counts counts) {
    }
}
