package com.zhishiku.kb.app.service.impl.health;

import com.zhishiku.kb.app.domain.req.health.HealthQuery;
import com.zhishiku.kb.app.enums.health.HealthSignal;
import com.zhishiku.kb.app.service.health.HealthFailure;
import java.util.Map;
import java.util.Set;

/** 严格、有界的公开查询输入解析。 */
public final class HealthQueries {
    private HealthQueries() {
    }

    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw HealthFailure.invalid();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            throw HealthFailure.invalid();
        }
    }

    public static HealthQuery parse(Map<String, String[]> input) {
        for (var entry : input.entrySet()) {
            if (!Set.of("signal", "limit", "cursor").contains(entry.getKey()) || entry.getValue() == null
                    || entry.getValue().length != 1 || entry.getValue()[0] == null || entry.getValue()[0].isEmpty()) {
                throw HealthFailure.invalid();
            }
        }
        String signal = input.containsKey("signal") ? input.get("signal")[0] : "ALL";
        String limit = input.containsKey("limit") ? input.get("limit")[0] : "20";
        long size = id(limit);
        if (size > 100) {
            throw HealthFailure.invalid();
        }
        return validate(new HealthQuery(signal, (int) size, input.containsKey("cursor") ? input.get("cursor")[0] : null));
    }

    public static HealthQuery validate(HealthQuery query) {
        if (query == null || query.signal() == null || query.limit() < 1 || query.limit() > 100
                || query.cursor() != null && (query.cursor().isEmpty() || query.cursor().length() > 2048)) {
            throw HealthFailure.invalid();
        }
        if (!"ALL".equals(query.signal())) {
            try {
                HealthSignal.valueOf(query.signal());
            } catch (IllegalArgumentException ignored) {
                throw HealthFailure.invalid();
            }
        }
        return query;
    }
}
