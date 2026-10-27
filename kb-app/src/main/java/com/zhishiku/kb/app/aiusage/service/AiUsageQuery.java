package com.zhishiku.kb.app.aiusage.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.conversation.RequestSignature;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * AI 查询专用版本游标，绑定窗口、筛选和事件提交上界，不代表授权。
 */
public final class AiUsageQuery {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> BASE = Set.of("userId", "departmentId", "mode", "generationStatus", "usageStatus", "settlementStatus", "from", "to");
    private static final Set<String> CURSOR_FIELDS = Set.of("v", "kind", "digest", "from", "to", "upper", "id", "time", "group");
    public final Map<String, Object> sql = new HashMap<>();
    public final Instant from;
    public final Instant to;
    public final int limit;
    public final String kind;
    public final String groupBy;
    public long upper;
    private final String digest;

    public AiUsageQuery(String kind, Map<String, List<String>> raw, Instant now) {
        this.kind = kind;
        boolean audits = "audits".equals(kind), groups = "groups".equals(kind);
        Map<String, String> input = new HashMap<>();
        raw.forEach((key, values) -> {
            if (!BASE.contains(key) && !(audits && Set.of("callId", "eventType", "limit", "cursor").contains(key))
                    && !(groups && Set.of("groupBy", "limit", "cursor").contains(key))) throw QueryException.invalid();
            if (values == null || values.size() != 1 || values.get(0) == null || values.get(0).isEmpty()
                    || values.get(0).length() > 4096) throw QueryException.invalid();
            input.put(key, values.get(0));
        });
        for (String id : List.of("userId", "departmentId")) {
            if (input.containsKey(id)) sql.put(id, positive(input.get(id)));
        }
        enumeration(input, "mode", AiObservation.MODES);
        enumeration(input, "generationStatus", AiObservation.GENERATIONS);
        enumeration(input, "usageStatus", AiObservation.USAGES);
        enumeration(input, "settlementStatus", AiObservation.SETTLEMENTS);
        enumeration(input, "eventType", AiObservation.KINDS);
        if (input.containsKey("callId")) sql.put("callId", uuid(input.get("callId")));
        try { limit = input.containsKey("limit") ? Integer.parseInt(input.get("limit")) : 20; }
        catch (NumberFormatException error) { throw QueryException.invalid(); }
        if (limit < 1 || limit > 100) throw QueryException.invalid();
        groupBy = groups ? input.getOrDefault("groupBy", "day") : null;
        if (groups && !Set.of("day", "department", "user").contains(groupBy)) throw QueryException.invalid();
        var filters = new TreeMap<>(sql);
        filters.put("limit", limit);
        if (groups) filters.put("groupBy", groupBy);
        digest = RequestSignature.digest(stringify(filters));
        JsonNode cursor = input.containsKey("cursor") ? decode(input.get("cursor")) : null;
        Instant end = now.truncatedTo(ChronoUnit.MICROS), start = end.minusSeconds(7L * 86400);
        if (cursor != null) {
            if (!kind.equals(cursor.path("kind").asText()) || !digest.equals(cursor.path("digest").asText())) throw QueryException.invalid();
            start = instant(cursor.path("from").asText()); end = instant(cursor.path("to").asText());
            upper = nonnegative(cursor.path("upper").asText());
            if (audits) {
                long last = positive(cursor.path("id").asText());
                Instant time = instant(cursor.path("time").asText());
                if (last > upper || time.isBefore(start) || !time.isBefore(end)) throw QueryException.invalid();
                sql.put("lastId", last); sql.put("lastTime", time);
            } else {
                String group = cursor.path("group").asText();
                if ("day".equals(groupBy)) {
                    try { LocalDate.parse(group); } catch (RuntimeException error) { throw QueryException.invalid(); }
                } else if (!group.matches("[0-9]{19}") || nonnegative(group) < 1) throw QueryException.invalid();
                sql.put("afterGroup", group);
            }
        }
        if (input.containsKey("from") != input.containsKey("to")) throw QueryException.invalid();
        if (input.containsKey("from")) {
            Instant suppliedFrom = instant(input.get("from")), suppliedTo = instant(input.get("to"));
            if (cursor != null && (!start.equals(suppliedFrom) || !end.equals(suppliedTo))) throw QueryException.invalid();
            start = suppliedFrom; end = suppliedTo;
        }
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0) throw QueryException.invalid();
        from = start; to = end;
        sql.put("from", from); sql.put("to", to); sql.put("fetch", limit + 1);
        sql.put("groupBy", groupBy);
    }

    public boolean continuing() { return sql.containsKey("lastId"); }

    public String cursor(long id, Instant time, String group) {
        var value = new TreeMap<String, Object>();
        value.put("v", "1"); value.put("kind", kind); value.put("digest", digest);
        value.put("from", from.toString()); value.put("to", to.toString()); value.put("upper", Long.toString(upper));
        value.put("id", Long.toString(id)); value.put("time", time == null ? null : time.toString()); value.put("group", group);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(stringify(value).getBytes(StandardCharsets.UTF_8));
    }

    private void enumeration(Map<String, String> input, String key, Set<String> allowed) {
        if (!input.containsKey(key)) return;
        if (!allowed.contains(input.get(key))) throw QueryException.invalid();
        sql.put(key, input.get(key));
    }

    private static JsonNode decode(String token) {
        try {
            if (token.length() > 4096 || !token.matches("[A-Za-z0-9_-]+")) throw QueryException.invalid();
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length > 3072) throw QueryException.invalid();
            JsonNode value;
            try (var parser = JSON.createParser(bytes)) {
                value = JSON.readTree(parser);
                if (parser.nextToken() != null) throw QueryException.invalid();
            }
            if (!value.isObject() || value.size() != CURSOR_FIELDS.size() || !"1".equals(value.path("v").asText())) throw QueryException.invalid();
            value.fieldNames().forEachRemaining(field -> { if (!CURSOR_FIELDS.contains(field)) throw QueryException.invalid(); });
            for (String field : List.of("v", "kind", "digest", "from", "to", "upper", "id"))
                if (!value.path(field).isTextual()) throw QueryException.invalid();
            for (String field : List.of("time", "group"))
                if (!value.path(field).isNull() && !value.path(field).isTextual()) throw QueryException.invalid();
            if (!value.path("digest").asText().matches("[0-9a-f]{64}")) throw QueryException.invalid();
            return value;
        } catch (Exception error) { throw QueryException.invalid(); }
    }

    public static String uuid(String value) {
        try {
            if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw QueryException.invalid();
            return UUID.fromString(value).toString();
        } catch (RuntimeException error) { throw QueryException.invalid(); }
    }

    private static long positive(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw QueryException.invalid();
        return nonnegative(value);
    }
    private static long nonnegative(String value) {
        try { if (!value.matches("[0-9]{1,19}")) throw QueryException.invalid(); return Long.parseLong(value); }
        catch (RuntimeException error) { throw QueryException.invalid(); }
    }
    private static Instant instant(String value) {
        try { return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS); }
        catch (RuntimeException error) { throw QueryException.invalid(); }
    }
    private static String stringify(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception error) { throw QueryException.invalid(); }
    }
}
