package com.zhishiku.kb.app.knowledgegap.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapCategory;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapSource;
import com.zhishiku.kb.app.query.QueryException;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.Enumeration;
import java.util.UUID;

/** 本域独立的有界严格 JSON 解析，不改变既有模块的序列化配置。 */
public final class KnowledgeGapInput {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(KnowledgeGapInputs.MAX_BODY_BYTES)
                    .maxNumberLength(19).build()).build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private KnowledgeGapInput() {
    }

    public static KnowledgeGapInputs.Search search(InputStream input) {
        JsonNode node = object(input, Set.of("nameQuery", "limit", "cursor"));
        JsonNode limitNode = node.get("limit");
        int limit = 20;
        if (limitNode != null) {
            if (!limitNode.isIntegralNumber() || !limitNode.canConvertToInt()) {
                throw KnowledgeGapInputs.invalid();
            }
            limit = limitNode.intValue();
        }
        return new KnowledgeGapInputs.Search(optionalText(node, "nameQuery"), limit, optionalText(node, "cursor"));
    }

    public static KnowledgeGapInputs.Creation create(InputStream input) {
        JsonNode node = object(input, Set.of("category", "description", "source", "shareAcknowledged"));
        JsonNode acknowledged = node.get("shareAcknowledged");
        if (acknowledged == null || !acknowledged.isBoolean()) {
            throw KnowledgeGapInputs.invalid();
        }
        try {
            return new KnowledgeGapInputs.Creation(KnowledgeGapCategory.valueOf(requiredText(node, "category")),
                    requiredText(node, "description"), KnowledgeGapSource.valueOf(requiredText(node, "source")),
                    acknowledged.booleanValue());
        } catch (IllegalArgumentException invalid) {
            throw KnowledgeGapInputs.invalid();
        }
    }

    public static KnowledgeGapInputs.Assignment assignment(InputStream input) {
        JsonNode node = object(input, Set.of("expectedVersion", "assigneeUserId"));
        return new KnowledgeGapInputs.Assignment(integer(node, "expectedVersion"), integer(node, "assigneeUserId"));
    }

    public static KnowledgeGapInputs.Solution solution(InputStream input) {
        JsonNode node = object(input, Set.of("expectedVersion", "text", "documentId", "versionId"));
        return new KnowledgeGapInputs.Solution(integer(node, "expectedVersion"), requiredText(node, "text"),
                optionalInteger(node, "documentId"), optionalInteger(node, "versionId"));
    }

    public static KnowledgeGapInputs.Confirmation confirmation(InputStream input) {
        JsonNode node = object(input, Set.of("expectedVersion", "solutionId"));
        return new KnowledgeGapInputs.Confirmation(integer(node, "expectedVersion"), identity(node, "solutionId"));
    }

    public static KnowledgeGapInputs.Rejection rejection(InputStream input) {
        JsonNode node = object(input, Set.of("expectedVersion", "solutionId", "reason"));
        return new KnowledgeGapInputs.Rejection(integer(node, "expectedVersion"), identity(node, "solutionId"), requiredText(node, "reason"));
    }

    public static KnowledgeGapInputs.Message note(InputStream input) {
        return message(input, "text");
    }

    public static KnowledgeGapInputs.Message reason(InputStream input) {
        return message(input, "reason");
    }

    public static String requestId(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders("X-Request-Id");
        if (headers == null || !headers.hasMoreElements()) {
            throw KnowledgeGapInputs.invalid();
        }
        String value = headers.nextElement();
        if (headers.hasMoreElements()) {
            throw KnowledgeGapInputs.invalid();
        }
        return KnowledgeGapInputs.requestId(value);
    }

    public static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw KnowledgeGapInputs.invalid();
        }
    }

    private static JsonNode object(InputStream input, Set<String> allowed) {
        try {
            byte[] bytes = input.readNBytes(KnowledgeGapInputs.MAX_BODY_BYTES + 1);
            if (bytes.length > KnowledgeGapInputs.MAX_BODY_BYTES) {
                throw new QueryException(413, "知识问题单请求过大");
            }
            if (bytes.length == 0) {
                throw KnowledgeGapInputs.invalid();
            }
            try (var parser = JSON.createParser(bytes)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || !node.isObject() || parser.nextToken() != null) {
                    throw KnowledgeGapInputs.invalid();
                }
                var names = node.fieldNames();
                while (names.hasNext()) {
                    if (!allowed.contains(names.next())) {
                        throw KnowledgeGapInputs.invalid();
                    }
                }
                return node;
            }
        } catch (IOException | IllegalArgumentException invalid) {
            throw KnowledgeGapInputs.invalid();
        }
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw KnowledgeGapInputs.invalid();
        }
        return value.textValue();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw KnowledgeGapInputs.invalid();
        }
        return value;
    }

    private static KnowledgeGapInputs.Message message(InputStream input, String field) {
        JsonNode node = object(input, Set.of("expectedVersion", field));
        return new KnowledgeGapInputs.Message(integer(node, "expectedVersion"), requiredText(node, field));
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw KnowledgeGapInputs.invalid();
        }
        return value.longValue();
    }

    private static Long optionalInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : integer(node, field);
    }

    private static UUID identity(JsonNode node, String field) {
        String value = requiredText(node, field);
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw KnowledgeGapInputs.invalid();
        }
        return UUID.fromString(value);
    }
}
