package com.zhishiku.kb.app.document.applicability.support;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityUpdate;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.common.conversation.SourceKey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.INVALID_INPUT;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.PAYLOAD_TOO_LARGE;

/** 有界、严格的纯输入解析；异常不包含请求正文。 */
public final class ValidityInput {
    public static final int BODY_LIMIT = 16384;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(BODY_LIMIT).maxNumberLength(20).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ValidityInput() {
    }

    public static ObjectNode body(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(BODY_LIMIT + 1);
            if (bytes.length > BODY_LIMIT) {
                throw new ValidityException(PAYLOAD_TOO_LARGE);
            }
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            unicode(raw);
            try (var parser = JSON.createParser(raw)) {
                JsonNode node = JSON.readTree(parser);
                if (!(node instanceof ObjectNode object) || parser.nextToken() != null) {
                    throw invalid();
                }
                validateStrings(object);
                return object;
            }
        } catch (ValidityException error) {
            throw error;
        } catch (IOException | RuntimeException ignored) {
            throw invalid();
        }
    }

    private static void validateStrings(JsonNode node) {
        if (node.isTextual()) {
            unicode(node.textValue());
        } else if (node.isContainerNode()) {
            if (node.isObject()) {
                node.fieldNames().forEachRemaining(ValidityInput::unicode);
            }
            node.forEach(ValidityInput::validateStrings);
        }
    }

    public static void fields(ObjectNode body, Set<String> allowed, Set<String> required) {
        body.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) {
                throw invalid();
            }
        });
        for (String key : required) {
            if (!body.has(key)) {
                throw invalid();
            }
        }
    }

    public static ApplicabilityUpdate applicability(ObjectNode body) {
        Set<String> keys = Set.of("effectiveFrom", "untilKind", "effectiveUntil", "reason", "expectedApplicabilityVersion");
        fields(body, keys, keys);
        try {
            var input = new ApplicabilityUpdate(date(body.get("effectiveFrom")), UntilKind.valueOf(rawText(body.get("untilKind"))),
                    date(body.get("effectiveUntil")), text(body.get("reason"), 2000, true, false, false),
                    number(body.get("expectedApplicabilityVersion"), 0, Long.MAX_VALUE));
            input.declaration();
            return input;
        } catch (IllegalArgumentException ignored) {
            throw invalid();
        }
    }

    public static List<SourceKey> sources(ObjectNode body) {
        fields(body, Set.of("items"), Set.of("items"));
        JsonNode items = body.get("items");
        if (!items.isArray() || items.size() < 1 || items.size() > 100) {
            throw invalid();
        }
        var result = new ArrayList<SourceKey>();
        var seen = new HashSet<SourceKey>();
        for (JsonNode item : items) {
            if (!(item instanceof ObjectNode object)) {
                throw invalid();
            }
            fields(object, Set.of("documentId", "versionId"), Set.of("documentId", "versionId"));
            var key = new SourceKey(number(item.get("documentId"), 1, Long.MAX_VALUE), number(item.get("versionId"), 1, Long.MAX_VALUE));
            if (!seen.add(key)) {
                throw invalid();
            }
            result.add(key);
        }
        return List.copyOf(result);
    }

    public static long id(String raw) {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            throw invalid();
        }
    }

    public static long number(JsonNode node, long min, long max) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < min || node.longValue() > max) {
            throw invalid();
        }
        return node.longValue();
    }

    public static Long nullableId(JsonNode node) {
        return node == null || node.isNull() ? null : number(node, 1, Long.MAX_VALUE);
    }

    public static String rawText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw invalid();
        }
        unicode(node.textValue());
        return node.textValue();
    }

    public static LocalDate date(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = rawText(node);
        if (!raw.matches("[12][0-9]{3}-[0-9]{2}-[0-9]{2}")) {
            throw invalid();
        }
        try {
            LocalDate date = LocalDate.parse(raw);
            if (date.getYear() < 1900 || date.getYear() > 2999) {
                throw invalid();
            }
            return date;
        } catch (DateTimeException | IllegalArgumentException ignored) {
            throw invalid();
        }
    }

    public static String text(JsonNode node, int max, boolean nullable, boolean emptyAllowed, boolean noControl) {
        if ((node == null || node.isNull()) && nullable) {
            return null;
        }
        String text = rawText(node);
        int start = 0;
        int end = text.length();
        while (start < end && whitespace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && whitespace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        text = text.substring(start, end);
        if (text.codePointCount(0, text.length()) > max || (!emptyAllowed && text.isEmpty())
                || (noControl && text.codePoints().anyMatch(Character::isISOControl))) {
            throw invalid();
        }
        return text;
    }

    public static String requestId(String raw) {
        return text(raw == null ? null : JSON.getNodeFactory().textNode(raw), 128, false, false, true);
    }

    public static String cursor(JsonNode node) {
        if (node == null) {
            return null;
        }
        String raw = rawText(node);
        if (!raw.matches("[A-Za-z0-9_-]{1,4096}")) {
            throw invalid();
        }
        return raw;
    }

    public static ObjectNode query(Map<String, String[]> query, Set<String> allowed) {
        ObjectNode node = JSON.createObjectNode();
        query.forEach((key, values) -> {
            if (!allowed.contains(key) || values == null || values.length != 1) {
                throw invalid();
            }
            unicode(values[0]);
            if ("limit".equals(key)) {
                node.put(key, id(values[0]));
            } else {
                node.put(key, values[0]);
            }
        });
        return node;
    }

    public static int limit(JsonNode node) {
        return node == null ? 20 : (int) number(node, 1, 100);
    }

    public static String literalPattern(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static void unicode(String value) {
        if (value == null) {
            throw invalid();
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw invalid();
                }
            } else if (character == 0 || Character.isLowSurrogate(character)) {
                throw invalid();
            }
        }
    }

    private static boolean whitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    public static ValidityException invalid() {
        return new ValidityException(INVALID_INPUT);
    }
}
