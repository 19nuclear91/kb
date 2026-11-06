package com.zhishiku.kb.app.auth.lifecycle;

import java.time.Instant;

/** 内部登记事实，禁止作为管理接口响应直接序列化。 */
public record LifecycleAssignment(long userId, Kind kind, String source, String enterpriseId,
                                  String employeeId, String connectionId, Restriction restriction,
                                  long version, Long lastEventId, Instant restoredAt,
                                  long registeredBy, Instant createdAt, Instant updatedAt) {
    public enum Kind {
        NONE, MANAGED, EMERGENCY
    }

    public enum Restriction {
        CLEAR, OFFBOARDED, REVIEW
    }

    @Override
    public String toString() {
        return "LifecycleAssignment[kind=" + kind + ", restriction=" + restriction + "]";
    }
}
