package com.zhishiku.kb.app.query;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 有界游标；过滤摘要只绑定查询条件，从不代表授权。
 */
public final class QueryCursor {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> FIELDS = Set.of("version", "kind", "digest", "id", "time", "from", "to");
    private final String kind;
    private final String digest;
    private final long id;
    private final Instant time;
    private final Instant from;
    private final Instant to;

    private QueryCursor(String kind, String digest, long id, Instant time, Instant from, Instant to) {
        this.kind = kind;
        this.digest = digest;
        this.id = id;
        this.time = time;
        this.from = from;
        this.to = to;
    }

    public long id() {
        return id;
    }

    public Instant time() {
        return time;
    }

    public Instant from() {
        return from;
    }

    public Instant to() {
        return to;
    }

    public void verify(String expectedKind, Map<String, Object> filters) {
        if (!kind.equals(expectedKind) || !digest.equals(digest(filters))) throw QueryException.invalid();
    }

    public static String encode(String kind, Map<String, Object> filters, long id) {
        return encode(kind, filters, id, null, null, null);
    }

    public static String encode(String kind, Map<String, Object> filters, long id,
                                Instant time, Instant from, Instant to) {
        if (id <= 0) throw new IllegalArgumentException("游标位置必须为正数");
        ObjectNode node = JSON.createObjectNode();
        node.put("version", 1).put("kind", kind).put("digest", digest(filters)).put("id", id);
        if (time != null) node.put("time", time.toString());
        if (from != null) node.put("from", from.toString());
        if (to != null) node.put("to", to.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static QueryCursor decode(String token, String expectedKind) {
        if (token == null) return null;
        if (token.length() > 2731 || !token.matches("[A-Za-z0-9_-]+")) throw QueryException.invalid();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length > 2048) throw QueryException.invalid();
            JsonNode node;
            try (var parser = JSON.createParser(bytes)) {
                node = JSON.readTree(parser);
                if (parser.nextToken() != null) throw QueryException.invalid();
            }
            if (node == null || !node.isObject() || !node.path("version").isIntegralNumber()
                    || node.path("version").intValue() != 1 || !node.path("version").canConvertToInt()
                    || !node.path("id").isIntegralNumber() || !node.path("id").canConvertToLong()
                    || node.path("id").longValue() <= 0) throw QueryException.invalid();
            var fields = node.fieldNames();
            while (fields.hasNext()) if (!FIELDS.contains(fields.next())) throw QueryException.invalid();
            String kind = string(node, "kind");
            String hash = string(node, "digest");
            if (!expectedKind.equals(kind) || hash == null || !hash.matches("[0-9a-f]{64}"))
                throw QueryException.invalid();
            Instant time = instant(node, "time"), from = instant(node, "from"), to = instant(node, "to");
            if (expectedKind.equals("audit")) {
                if (time == null || from == null || to == null || time.isBefore(from) || !time.isBefore(to)) {
                    throw QueryException.invalid();
                }
            } else if (expectedKind.equals("document-review-pending")) {
                if (time == null || from != null || to != null) throw QueryException.invalid();
            } else if (time != null || from != null || to != null) throw QueryException.invalid();
            return new QueryCursor(kind, hash, node.get("id").longValue(), time, from, to);
        } catch (QueryException error) {
            throw error;
        } catch (Exception ignored) {
            throw QueryException.invalid();
        }
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw QueryException.invalid();
        return value.textValue();
    }

    private static Instant instant(JsonNode node, String field) {
        return node.has(field) ? QueryParameters.parseInstant(string(node, field)) : null;
    }

    private static String digest(Map<String, Object> filters) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(new TreeMap<>(filters))));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("缺少摘要算法");
        } catch (Exception error) {
            throw new IllegalArgumentException("过滤条件无法规范化");
        }
    }
}
