package com.zhishiku.kb.app.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 新查询参数的严格解析；所有失败只返回固定错误。
 */
public final class QueryParameters {
    private final Map<String, String> values = new LinkedHashMap<>();

    public QueryParameters(Map<String, List<String>> input, String... allowed) {
        Set<String> names = Set.of(allowed);
        input.forEach((name, entries) -> {
            if (!names.contains(name) || entries == null || entries.size() != 1
                    || entries.get(0) == null || entries.get(0).isBlank()) throw QueryException.invalid();
            values.put(name, entries.get(0));
        });
    }

    public String text(String name, int max) {
        String value = values.get(name);
        if (value == null) return null;
        value = value.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) throw QueryException.invalid();
        return value;
    }

    public String exactText(String name, int max) {
        String value = text(name, max);
        if (value != null && !value.equals(values.get(name))) throw QueryException.invalid();
        return value;
    }

    public String cursor() {
        return exactText("cursor", 2731);
    }

    public Long positive(String name) {
        String value = exactText(name, 19);
        return value == null ? null : positiveValue(value);
    }

    public static long positiveValue(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw QueryException.invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            throw QueryException.invalid();
        }
    }

    public int limit() {
        Long value = positive("limit");
        if (value != null && value > 100) throw QueryException.invalid();
        return value == null ? 20 : value.intValue();
    }

    public Boolean bool(String name) {
        String value = exactText(name, 5);
        if (value == null) return null;
        if (!value.equals("true") && !value.equals("false")) throw QueryException.invalid();
        return Boolean.valueOf(value);
    }

    public Instant instant(String name) {
        String value = exactText(name, 64);
        return value == null ? null : parseInstant(value);
    }

    public static Instant parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException | ArithmeticException ignored) {
            throw QueryException.invalid();
        }
    }

    public static String like(String value) {
        return value == null ? null : "%" + value.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /**
     * 仅将规范化条件交给摘要，不让原始参数进入游标。
     */
    public static Map<String, Object> filters(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) result.put((String) pairs[i], pairs[i + 1].toString());
        }
        return result;
    }
}
