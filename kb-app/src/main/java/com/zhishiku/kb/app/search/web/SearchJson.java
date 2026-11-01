package com.zhishiku.kb.app.search.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.search.model.SearchFailure;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** 搜索端点专用精确 JSON 校验，不更改全局解码策略。 */
final class SearchJson {
    private SearchJson() {
    }

    static ObjectNode object(JsonParser parser) throws IOException {
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode root = parser.readValueAsTree();
        if (!(root instanceof ObjectNode object) || parser.nextToken() != null) {
            throw invalid();
        }
        return object;
    }

    static void fields(ObjectNode root, Set<String> allowed) {
        var names = root.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw invalid();
            }
        }
    }

    static long number(JsonNode value, long minimum, long maximum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < minimum || value.longValue() > maximum) {
            throw invalid();
        }
        return value.longValue();
    }

    static Long nullableId(JsonNode value) {
        return value == null || value.isNull() ? null : number(value, 1, Long.MAX_VALUE);
    }

    static String text(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    static boolean bool(JsonNode value) {
        if (value == null) {
            return false;
        }
        if (!value.isBoolean()) {
            throw invalid();
        }
        return value.booleanValue();
    }

    static OffsetDateTime time(JsonNode value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text(value));
        } catch (DateTimeParseException invalidTime) {
            throw invalid();
        }
    }

    static SearchFailure invalid() {
        return new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
    }
}
