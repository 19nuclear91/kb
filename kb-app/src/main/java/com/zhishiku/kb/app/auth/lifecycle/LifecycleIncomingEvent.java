package com.zhishiku.kb.app.auth.lifecycle;

import java.time.Instant;
import java.util.List;

public record LifecycleIncomingEvent(String sourceScope, String eventId, String eventType, String enterpriseId,
                                     String connectionId, Instant bornAt, String digest, List<String> employees) {
    public LifecycleIncomingEvent {
        employees = List.copyOf(employees);
    }

    public boolean offboarding() {
        return "user_leave_org".equals(eventType);
    }

    @Override
    public String toString() {
        return "LifecycleIncomingEvent[offboarding=" + offboarding() + ",employees=" + employees.size() + "]";
    }
}
