package com.zhishiku.kb.app.auth.lifecycle;

import java.time.Instant;

public record CurrentEmployee(String source, String enterpriseId, String employeeId, String connectionId,
                              String displayName, Instant checkedAt) {
    @Override
    public String toString() {
        return "CurrentEmployee[redacted]";
    }
}
