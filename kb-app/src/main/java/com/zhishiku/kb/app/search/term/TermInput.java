package com.zhishiku.kb.app.search.term;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import static com.zhishiku.kb.app.search.term.TermException.Reason.INVALID_INPUT;
import static com.zhishiku.kb.app.search.term.TermException.Reason.PAYLOAD_TOO_LARGE;

/** 有界严格 JSON 解码与词典比较规则，不依赖 HTTP 或持久化。 */
public final class TermInput {
    public static final int BODY_LIMIT = 32768;
    public static final int EXPRESSION_LIMIT = 64;
    public static final int DESCRIPTION_LIMIT = 256;
    public static final int ALIAS_LIMIT = 20;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(BODY_LIMIT).maxNumberLength(20).build())
            .build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private TermInput() {
    }

    public static ObjectNode body(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(BODY_LIMIT + 1);
            if (bytes.length > BODY_LIMIT) {
                throw new TermException(PAYLOAD_TOO_LARGE);
            }
            try (JsonParser parser = JSON.createParser(bytes)) {
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

    public static void fields(ObjectNode body, Set<String> allowed, Set<String> required) {
        body.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw invalid();
            }
        });
        for (String field : required) {
            if (!body.has(field)) {
                throw invalid();
            }
        }
    }

    public static TermInputs.TermWrite term(ObjectNode body, boolean create) {
        Set<String> allowed = create ? Set.of("canonicalName", "description", "aliases")
                : Set.of("canonicalName", "description", "aliases", "expectedVersion");
        fields(body, allowed, allowed);
        String name = expression(rawText(body.get("canonicalName")));
        String description = text(rawText(body.get("description")), DESCRIPTION_LIMIT, false);
        JsonNode aliasValues = body.get("aliases");
        if (aliasValues == null || !aliasValues.isArray() || aliasValues.size() > ALIAS_LIMIT) {
            throw invalid();
        }
        Set<String> keys = new HashSet<>();
        keys.add(key(name));
        var aliases = new ArrayList<TermInputs.Alias>();
        for (JsonNode value : aliasValues) {
            if (!(value instanceof ObjectNode alias)) {
                throw invalid();
            }
            fields(alias, Set.of("text", "kind"), Set.of("text", "kind"));
            String display = expression(rawText(alias.get("text")));
            String lookup = key(display);
            TermRows.AliasKind kind;
            try {
                kind = TermRows.AliasKind.valueOf(rawText(alias.get("kind")));
            } catch (IllegalArgumentException ignored) {
                throw invalid();
            }
            if (!keys.add(lookup)) {
                throw invalid();
            }
            aliases.add(new TermInputs.Alias(display, lookup, kind));
        }
        aliases.sort(Comparator.comparing(TermInputs.Alias::key)
                .thenComparing(alias -> alias.kind().name()).thenComparing(TermInputs.Alias::text));
        return new TermInputs.TermWrite(name, key(name), description, aliases,
                create ? null : number(body.get("expectedVersion"), 0, Long.MAX_VALUE));
    }

    public static TermInputs.CorrectionWrite correction(ObjectNode body, boolean create) {
        Set<String> allowed = create ? Set.of("wrongExpression", "termId")
                : Set.of("wrongExpression", "termId", "expectedVersion");
        fields(body, allowed, allowed);
        String expression = expression(rawText(body.get("wrongExpression")));
        return new TermInputs.CorrectionWrite(expression, key(expression), number(body.get("termId"), 1, Long.MAX_VALUE),
                create ? null : number(body.get("expectedVersion"), 0, Long.MAX_VALUE));
    }

    public static TermInputs.EnabledWrite enabled(ObjectNode body) {
        fields(body, Set.of("enabled", "expectedVersion"), Set.of("enabled", "expectedVersion"));
        return new TermInputs.EnabledWrite(bool(body.get("enabled")), number(body.get("expectedVersion"), 0, Long.MAX_VALUE));
    }

    public static TermInputs.Query query(ObjectNode body) {
        fields(body, Set.of("nameQuery", "enabled", "limit", "cursor"), Set.of());
        String name = body.hasNonNull("nameQuery") ? rawText(body.get("nameQuery")) : null;
        Boolean enabled = body.has("enabled") ? bool(body.get("enabled")) : null;
        int limit = body.has("limit") ? (int) number(body.get("limit"), 1, 100) : 20;
        String cursor = body.has("cursor") ? rawText(body.get("cursor")) : null;
        return new TermInputs.Query(name, enabled, limit, cursor);
    }

    public static TermInputs.MeaningQuery meaningQuery(ObjectNode body) {
        fields(body, Set.of("expression", "limit", "cursor"), Set.of("expression"));
        return new TermInputs.MeaningQuery(rawText(body.get("expression")), body.has("limit") ? (int) number(body.get("limit"), 1, 100) : 20,
                body.has("cursor") ? rawText(body.get("cursor")) : null);
    }

    public static String commandQuery(ObjectNode body) {
        fields(body, Set.of("requestId"), Set.of("requestId"));
        return requestId(rawText(body.get("requestId")));
    }

    public static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw invalid();
        }
    }

    public static String expression(String value) {
        return text(value, EXPRESSION_LIMIT, false);
    }

    public static String text(String value, int max, boolean emptyAllowed) {
        return text(value, max, emptyAllowed, true);
    }

    public static String requestId(String value) {
        return text(value, 128, false, false);
    }

    public static TermInputs.TermWrite normalized(TermInputs.TermWrite input, boolean create) {
        if (input == null) {
            throw invalid();
        }
        ObjectNode body = JSON.createObjectNode().put("canonicalName", input.canonicalName()).put("description", input.description());
        var aliases = body.putArray("aliases");
        for (TermInputs.Alias alias : input.aliases()) {
            if (alias == null || alias.kind() == null) {
                throw invalid();
            }
            aliases.addObject().put("text", alias.text()).put("kind", alias.kind().name());
        }
        if (!create) {
            if (input.expectedVersion() == null) {
                throw invalid();
            }
            body.put("expectedVersion", input.expectedVersion());
        } else if (input.expectedVersion() != null) {
            throw invalid();
        }
        return term(body, create);
    }

    public static TermInputs.CorrectionWrite normalized(TermInputs.CorrectionWrite input, boolean create) {
        if (input == null) {
            throw invalid();
        }
        ObjectNode body = JSON.createObjectNode().put("wrongExpression", input.wrongExpression()).put("termId", input.termId());
        if (!create) {
            if (input.expectedVersion() == null) {
                throw invalid();
            }
            body.put("expectedVersion", input.expectedVersion());
        } else if (input.expectedVersion() != null) {
            throw invalid();
        }
        return correction(body, create);
    }

    private static String text(String value, int max, boolean emptyAllowed, boolean nfc) {
        if (value == null) {
            throw invalid();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw invalid();
                }
            } else if (Character.isLowSurrogate(c)) {
                throw invalid();
            }
        }
        int start = 0;
        int end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && whitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        String result = value.substring(start, end);
        if (nfc) {
            result = Normalizer.normalize(result, Normalizer.Form.NFC);
        }
        if ((!emptyAllowed && result.isEmpty()) || result.codePointCount(0, result.length()) > max
                || result.codePoints().anyMatch(cp -> Character.getType(cp) == Character.CONTROL)) {
            throw invalid();
        }
        return result;
    }

    public static String key(String normalized) {
        StringBuilder key = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            key.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return key.toString();
    }

    public static String literalPattern(String key) {
        return key == null ? null : "%" + key.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static String rawText(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    public static long number(JsonNode value, long min, long max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < min || value.longValue() > max) {
            throw invalid();
        }
        return value.longValue();
    }

    public static boolean bool(JsonNode value) {
        if (value == null || !value.isBoolean()) {
            throw invalid();
        }
        return value.booleanValue();
    }

    private static boolean whitespace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    public static TermException invalid() {
        return new TermException(INVALID_INPUT);
    }
}
