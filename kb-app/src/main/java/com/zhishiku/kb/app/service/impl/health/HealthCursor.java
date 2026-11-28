package com.zhishiku.kb.app.service.impl.health;

import com.zhishiku.kb.app.domain.req.health.HealthQuery;
import com.zhishiku.kb.app.service.health.HealthFailure;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

/** 有界位置绑定；游标不授予权限，也不冻结跨页事实。 */
public final class HealthCursor {
    private HealthCursor() {
    }

    public static String encode(long actor, long space, HealthQuery query, LocalDate date, long lastId) {
        String payload = context(actor, space, query) + ":" + date + ":" + lastId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
    }

    public static Long decode(long actor, long space, HealthQuery query, LocalDate date) {
        String token = query.cursor();
        if (token == null) {
            return null;
        }
        try {
            if (token.isEmpty() || token.length() > 2048 || !token.matches("[A-Za-z0-9_-]+")) {
                throw HealthFailure.invalid();
            }
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
                throw HealthFailure.invalid();
            }
            String decoded = new String(bytes, StandardCharsets.US_ASCII);
            String prefix = context(actor, space, query) + ":";
            if (!decoded.startsWith(prefix)) {
                throw HealthFailure.invalid();
            }
            String[] position = decoded.substring(prefix.length()).split(":", -1);
            if (position.length != 2 || !position[0].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || !position[1].matches("[1-9][0-9]{0,18}")) {
                throw HealthFailure.invalid();
            }
            long id = Long.parseLong(position[1]);
            LocalDate bound = LocalDate.parse(position[0]);
            if (!bound.equals(date)) {
                throw new HealthFailure(409, "RADAR_DATE_CHANGED");
            }
            return id;
        } catch (HealthFailure error) {
            throw error;
        } catch (RuntimeException ignored) {
            throw HealthFailure.invalid();
        }
    }

    private static String context(long actor, long space, HealthQuery query) {
        return "KNOWLEDGE_HEALTH_V1:" + actor + ":" + space + ":" + query.signal() + ":" + query.limit();
    }
}
