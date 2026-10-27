package com.zhishiku.kb.app.query;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;

/** 新审核写端点的有界严格解析，不改变旧端点的 JSON 行为。 */
public final class CommandInput {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(8192).maxNumberLength(19).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private CommandInput() {
    }

    public static JsonNode object(InputStream input, Set<String> fields) {
        try {
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length > 16384) throw QueryException.invalid();
            try (var parser = JSON.createParser(bytes)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || !node.isObject() || node.size() != fields.size() || parser.nextToken() != null)
                    throw QueryException.invalid();
                var names = node.fieldNames();
                while (names.hasNext()) if (!fields.contains(names.next())) throw QueryException.invalid();
                return node;
            }
        } catch (IOException invalid) {
            throw QueryException.invalid();
        }
    }

    public static long nonnegative(JsonNode node) {
        if (node == null || (!node.isIntegralNumber() && !node.isTextual())) throw QueryException.invalid();
        String value = node.asText();
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw QueryException.invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw QueryException.invalid();
        }
    }

    public static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw QueryException.invalid();
        return node.textValue();
    }

    public static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) throw QueryException.invalid();
    }

    public static void noPayload(HttpServletRequest request) throws IOException {
        noQuery(request);
        if (request.getInputStream().read() != -1) throw QueryException.invalid();
    }

    public static String requestId(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("X-Request-Id"));
        if (values.size() != 1) throw QueryException.invalid();
        try {
            return ReviewCommandBinding.requireRequestId(values.getFirst());
        } catch (IllegalArgumentException invalid) {
            throw QueryException.invalid();
        }
    }
}
