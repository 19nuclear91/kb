package com.zhishiku.kb.app.document.metadata.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.INVALID_INPUT;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.PAYLOAD_TOO_LARGE;

/** 仅供资料与目录端点使用，不改变其他接口的解码策略。 */
public final class MetadataInput {
    public static final int WRITE_LIMIT = 65536;
    public static final int QUERY_LIMIT = 16384;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(WRITE_LIMIT).maxNumberLength(20).build())
            .build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private MetadataInput() {
    }

    public static ObjectNode body(HttpServletRequest request, int limit) {
        if (request.getQueryString() != null) {
            throw invalid();
        }
        try {
            return body(request.getInputStream(), limit);
        } catch (IOException ignored) {
            throw invalid();
        }
    }

    public static ObjectNode body(InputStream input, int limit) {
        try {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new MetadataException(PAYLOAD_TOO_LARGE);
            }
            try (var parser = JSON.createParser(bytes)) {
                JsonNode value = JSON.readTree(parser);
                if (!(value instanceof ObjectNode object) || parser.nextToken() != null) {
                    throw invalid();
                }
                return object;
            }
        } catch (IOException ignored) {
            throw invalid();
        }
    }

    public static void fields(ObjectNode object, Set<String> allowed, Set<String> required) {
        object.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) {
                throw invalid();
            }
        });
        for (String key : required) {
            if (!object.has(key)) {
                throw invalid();
            }
        }
    }

    public static MetadataInputs.Update update(ObjectNode body) {
        var keys = Set.of("title", "summary", "categoryId", "tagIds", "expectedMetadataVersion");
        fields(body, keys, keys);
        return new MetadataInputs.Update(text(body.get("title"), 512, false, false),
                text(body.get("summary"), 2000, true, false), nullableId(body.get("categoryId")),
                tagIds(body.get("tagIds"), false), number(body.get("expectedMetadataVersion"), 0, Long.MAX_VALUE));
    }

    public static MetadataInputs.CategoryWrite category(ObjectNode body) {
        fields(body, Set.of("name", "parentId", "sortOrder", "expectedTreeVersion"),
                Set.of("name", "parentId", "expectedTreeVersion"));
        String name = name(body.get("name"), 64);
        return new MetadataInputs.CategoryWrite(name, key(name), nullableId(body.get("parentId")),
                (int) optionalNumber(body.get("sortOrder"), 0, 0, Integer.MAX_VALUE),
                number(body.get("expectedTreeVersion"), 0, Long.MAX_VALUE));
    }

    public static long deleteCategory(ObjectNode body) {
        fields(body, Set.of("expectedTreeVersion"), Set.of("expectedTreeVersion"));
        return number(body.get("expectedTreeVersion"), 0, Long.MAX_VALUE);
    }

    public static MetadataInputs.TagWrite tag(ObjectNode body, boolean create) {
        Set<String> keys = create ? Set.of("name") : Set.of("name", "enabled", "expectedVersion");
        fields(body, keys, keys);
        String name = name(body.get("name"), 32);
        return new MetadataInputs.TagWrite(name, key(name), create || bool(body.get("enabled")),
                create ? null : number(body.get("expectedVersion"), 0, Long.MAX_VALUE));
    }

    public static MetadataInputs.Query query(ObjectNode body) {
        fields(body, Set.of("titleQuery", "spaceId", "categoryId", "uncategorized", "tagIds", "fileType",
                "updatedFrom", "updatedTo", "page", "size"), Set.of());
        Long category = nullableId(body.get("categoryId"));
        boolean uncategorized = body.has("uncategorized") && bool(body.get("uncategorized"));
        OffsetDateTime from = instant(body.get("updatedFrom")), to = instant(body.get("updatedTo"));
        if ((category != null && uncategorized) || (from != null && to != null && !from.isBefore(to))) {
            throw invalid();
        }
        MetadataInputs.FileType type = null;
        if (body.has("fileType")) {
            try {
                type = MetadataInputs.FileType.valueOf(rawText(body.get("fileType")));
            } catch (IllegalArgumentException ignored) {
                throw invalid();
            }
        }
        return new MetadataInputs.Query(text(body.get("titleQuery"), 128, true, false),
                nullableId(body.get("spaceId")), category, uncategorized, tagIds(body.get("tagIds"), true), type,
                from, to, (int) optionalNumber(body.get("page"), 1, 1, Integer.MAX_VALUE),
                (int) optionalNumber(body.get("size"), 20, 1, 100));
    }

    public static MetadataInputs.CategoryQuery categoryQuery(ObjectNode body, boolean filter) {
        fields(body, filter ? Set.of("spaceId", "parentId", "limit", "cursor") : Set.of("parentId", "limit", "cursor"),
                filter ? Set.of("spaceId") : Set.of());
        return new MetadataInputs.CategoryQuery(filter ? number(body.get("spaceId"), 1, Long.MAX_VALUE) : null,
                nullableId(body.get("parentId")), (int) optionalNumber(body.get("limit"), 20, 1, 100),
                cursor(body.get("cursor")));
    }

    public static MetadataInputs.TagQuery tagQuery(ObjectNode body, boolean manager) {
        fields(body, manager ? Set.of("nameQuery", "enabled", "limit", "cursor") : Set.of("nameQuery", "limit", "cursor"), Set.of());
        return new MetadataInputs.TagQuery(text(body.get("nameQuery"), 64, true, true),
                body.has("enabled") ? bool(body.get("enabled")) : null,
                (int) optionalNumber(body.get("limit"), 20, 1, 100), cursor(body.get("cursor")));
    }

    public static MetadataInputs.CategoryQuery categoryQuery(Map<String, String[]> query) {
        ObjectNode body = JSON.createObjectNode();
        query.forEach((key, values) -> {
            if (!Set.of("parentId", "limit", "cursor").contains(key) || values.length != 1) {
                throw invalid();
            }
            if ("cursor".equals(key)) {
                body.put(key, values[0]);
            } else {
                body.put(key, id(values[0]));
            }
        });
        return categoryQuery(body, false);
    }

    public static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            throw invalid();
        }
    }

    public static String requestId(String value) {
        return text(value == null ? null : JSON.getNodeFactory().textNode(value), 128, false, true);
    }

    public static String key(String name) {
        return Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    public static String literalPattern(String value) {
        if (value == null) {
            return null;
        }
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static long number(JsonNode value, long min, long max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < min || value.longValue() > max) {
            throw invalid();
        }
        return value.longValue();
    }

    public static Long nullableId(JsonNode value) {
        return value == null || value.isNull() ? null : number(value, 1, Long.MAX_VALUE);
    }

    public static String rawText(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String name(JsonNode value, int limit) {
        String normalized = Normalizer.normalize(rawText(value), Normalizer.Form.NFC);
        return text(JSON.getNodeFactory().textNode(normalized), limit, false, true);
    }

    private static String text(JsonNode value, int max, boolean nullable, boolean noControl) {
        if ((value == null || value.isNull()) && nullable) {
            return null;
        }
        String text = rawText(value);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw invalid();
                }
            } else if (Character.isLowSurrogate(c) || c == 0) {
                throw invalid();
            }
        }
        int start = 0, end = text.length();
        while (start < end && whitespace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && whitespace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        text = text.substring(start, end);
        if (text.codePointCount(0, text.length()) > max || (noControl && text.codePoints().anyMatch(Character::isISOControl))) {
            throw invalid();
        }
        if (text.isEmpty()) {
            if (nullable) {
                return null;
            }
            throw invalid();
        }
        return text;
    }

    private static boolean whitespace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static List<Long> tagIds(JsonNode value, boolean optional) {
        if (value == null && optional) {
            return List.of();
        }
        if (value == null || !value.isArray() || value.size() > 20) {
            throw invalid();
        }
        Set<Long> ids = new HashSet<>();
        for (JsonNode item : value) {
            if (!ids.add(number(item, 1, Long.MAX_VALUE))) {
                throw invalid();
            }
        }
        List<Long> sorted = new ArrayList<>(ids);
        sorted.sort(Long::compare);
        return List.copyOf(sorted);
    }

    private static long optionalNumber(JsonNode value, long defaultValue, long min, long max) {
        return value == null ? defaultValue : number(value, min, max);
    }

    private static boolean bool(JsonNode value) {
        if (value == null || !value.isBoolean()) {
            throw invalid();
        }
        return value.booleanValue();
    }

    private static OffsetDateTime instant(JsonNode value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rawText(value));
        } catch (DateTimeParseException ignored) {
            throw invalid();
        }
    }

    private static String cursor(JsonNode value) {
        if (value == null) {
            return null;
        }
        String text = rawText(value);
        if (!text.matches("[A-Za-z0-9_-]{1,4096}")) {
            throw invalid();
        }
        return text;
    }

    private static MetadataException invalid() {
        return new MetadataException(INVALID_INPUT);
    }
}
