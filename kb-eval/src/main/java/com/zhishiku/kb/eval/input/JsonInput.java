package com.zhishiku.kb.eval.input;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.eval.input.EvaluationException.Code;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * 严格 JSON 类型、实际字节摘要与只读本地文件操作。
 */
public final class JsonInput {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private JsonInput() {
    }

    /**
     * 从同一字节副本解析的清单。
     *
     * @param path   本地文件位置，只用于解析相对路径
     * @param object 受校验清单字段
     * @param sha256 实际字节摘要
     */
    public record Document(Path path, ObjectNode object, String sha256) {
    }

    public static Document readDocument(String file, String role) {
        Path path = localPath(file, null, role, "file");
        try {
            byte[] bytes = Files.readAllBytes(path);
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonParser parser = parser(content)) {
                JsonNode node = MAPPER.readTree(parser);
                if (node == null || !node.isObject() || parser.nextToken() != null) {
                    throw error(Code.INVALID_JSON, role, 0, "json");
                }
                return new Document(path, (ObjectNode) node, HexFormat.of().formatHex(digest().digest(bytes)));
            }
        } catch (CharacterCodingException e) {
            throw error(Code.INVALID_JSON, role, 0, "json");
        } catch (IOException e) {
            // 解析异常继承 IOException，只输出固定类别。
            if (e instanceof JsonProcessingException) {
                throw error(Code.INVALID_JSON, role, 0, "json");
            }
            throw error(Code.READ_FAILED, role, 0, "file");
        }
    }

    public static Path localPath(String file, Path parent, String role, String field) {
        if (file == null || file.isBlank() || file.startsWith("\\\\") || file.startsWith("//")
                || (file.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*") && !file.matches("^[A-Za-z]:[\\\\/].*"))) {
            throw error(Code.LOCAL_FILE_REQUIRED, role, 0, field);
        }
        try {
            Path path = Path.of(file);
            if (parent != null && !path.isAbsolute()) {
                path = parent.resolve(path);
            }
            path = path.toAbsolutePath().normalize();
            if (path.toString().startsWith("\\\\")) {
                throw error(Code.LOCAL_FILE_REQUIRED, role, 0, field);
            }
            if (!Files.isRegularFile(path)) {
                throw error(Code.LOCAL_FILE_REQUIRED, role, 0, field);
            }
            return path;
        } catch (InvalidPathException | SecurityException e) {
            throw error(Code.LOCAL_FILE_REQUIRED, role, 0, field);
        }
    }

    public static JsonParser parser(String line) throws IOException {
        return MAPPER.createParser(line);
    }

    /**
     * 只保留必要字段；未知顶层内容通过 skipChildren 跳过。
     */
    public static ObjectNode selectedObject(JsonParser parser, Set<String> fields, boolean allowExtra,
                                            String role, int line) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw error(Code.INVALID_JSON, role, line, "json");
        }
        ObjectNode object = MAPPER.createObjectNode();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw error(Code.INVALID_JSON, role, line, "json");
            }
            String name = parser.currentName();
            if (parser.nextToken() == null) {
                throw error(Code.INVALID_JSON, role, line, "json");
            }
            if (fields.contains(name)) {
                object.set(name, MAPPER.readTree(parser));
            } else if (allowExtra) {
                parser.skipChildren();
            } else {
                throw error(Code.INVALID_FIELD, role, line, "json");
            }
        }
        return object;
    }

    public static void fields(JsonNode node, Set<String> allowed, String role, int line) {
        if (!node.isObject()) {
            throw error(Code.INVALID_FIELD, role, line, "json");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw error(Code.INVALID_FIELD, role, line, "json");
            }
        });
    }

    public static void schema(JsonNode node, String role) {
        JsonNode schema = required(node, "schemaVersion", role, 0);
        if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != 1) {
            throw error(Code.UNSUPPORTED_SCHEMA, role, 0, "schemaVersion");
        }
    }

    public static JsonNode required(JsonNode node, String field, String role, int line) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw error(Code.INVALID_FIELD, role, line, field);
        }
        return value;
    }

    public static String text(JsonNode node, String field, String role, int line) {
        JsonNode value = required(node, field, role, line);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw error(Code.INVALID_FIELD, role, line, field);
        }
        return value.textValue();
    }

    public static String identifier(JsonNode node, String field, String role, int line) {
        String value = text(node, field, role, line);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw error(Code.INVALID_FIELD, role, line, field);
        }
        return value;
    }

    public static long positiveId(JsonNode node, String field, String role, int line) {
        JsonNode value = required(node, field, role, line);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw error(Code.INVALID_FIELD, role, line, field);
        }
        return value.longValue();
    }

    public static double finite(JsonNode node, String field, String role, int line) {
        JsonNode value = required(node, field, role, line);
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw error(Code.INVALID_FIELD, role, line, field);
        }
        return value.doubleValue();
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public static EvaluationException error(Code code, String role, int line, String field) {
        return new EvaluationException(code, role, line, field);
    }
}
