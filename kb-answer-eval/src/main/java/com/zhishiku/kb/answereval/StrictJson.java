package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** 严格解析和确定性序列化；不向调用方传播 Jackson 的原文诊断。 */
public final class StrictJson {
    public static final int MANIFEST_LIMIT = 1024 * 1024;
    public static final int FILE_LIMIT = 64 * 1024 * 1024;
    public static final int LINE_LIMIT = 512 * 1024;
    public static final int CASE_LIMIT = 1000;
    private static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                            .maxStringLength(FILE_LIMIT).maxNumberLength(100).build()).build())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private StrictJson() {
    }

    public static JsonNode parse(byte[] bytes) {
        try {
            String text = utf8(bytes);
            try (JsonParser parser = MAPPER.createParser(text)) {
                JsonNode result = MAPPER.readTree(parser);
                Checks.require(result != null && result.isObject() && parser.nextToken() == null);
                validateText(result);
                return result;
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new EvaluationFailure(EvaluationFailure.Code.INPUT);
        }
    }

    public static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IOException failure) {
            throw new EvaluationFailure(EvaluationFailure.Code.INPUT);
        }
    }

    public static <T> T read(byte[] bytes, Class<T> type) {
        return convert(parse(bytes), type);
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        try {
            validateText(node);
            return MAPPER.treeToValue(node, type);
        } catch (IOException | IllegalArgumentException failure) {
            throw new EvaluationFailure(EvaluationFailure.Code.INPUT);
        }
    }

    public static <T> List<T> lines(byte[] bytes, Class<T> type, int maxRows) {
        List<T> rows = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= bytes.length; i++) {
            if (i < bytes.length && bytes[i] != '\n' && bytes[i] != '\r') {
                if (i - start >= LINE_LIMIT) {
                    throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
                }
                continue;
            }
            if (i == bytes.length && start == i) {
                break;
            }
            if (rows.size() >= maxRows) {
                throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
            }
            rows.add(read(Arrays.copyOfRange(bytes, start, i), type));
            if (i + 1 < bytes.length && bytes[i] == '\r' && bytes[i + 1] == '\n') {
                i++;
            }
            start = i + 1;
        }
        return List.copyOf(rows);
    }

    public static byte[] bounded(InputStream stream, int limit, Runnable guard) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        while (true) {
            guard.run();
            int count = stream.read(buffer, 0, Math.min(buffer.length, limit - result.size() + 1));
            guard.run();
            if (count < 0) {
                return result.toByteArray();
            }
            if (count + result.size() > limit) {
                throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
            }
            result.write(buffer, 0, count);
        }
    }

    public static byte[] bytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(canonical(MAPPER.valueToTree(value)));
        } catch (IOException | IllegalArgumentException failure) {
            throw new EvaluationFailure(EvaluationFailure.Code.OUTPUT);
        }
    }

    public static byte[] jsonl(List<?> values) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (Object value : values) {
            byte[] row = bytes(value);
            if (row.length > LINE_LIMIT || (long) result.size() + row.length + 1 > FILE_LIMIT) {
                throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
            }
            result.writeBytes(row);
            result.write('\n');
        }
        return result.toByteArray();
    }

    public static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public static String textSha(String text) {
        Checks.unicode(text);
        return sha(text.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> sorted.set(name, canonical(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node;
    }

    private static void validateText(JsonNode node) {
        if (node.isTextual()) {
            Checks.unicode(node.textValue());
        }
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(Checks::unicode);
        }
        if (node.isContainerNode()) {
            node.forEach(StrictJson::validateText);
        }
    }
}
