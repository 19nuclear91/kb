package com.zhishiku.kb.eval.collector;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static com.zhishiku.kb.eval.collector.CollectionException.Code.INPUT;
import static com.zhishiku.kb.eval.collector.CollectionException.Code.LIMIT;

final class JsonSupport {
    static final int MANIFEST_LIMIT = 1024 * 1024;
    static final int LINES_LIMIT = 32 * 1024 * 1024;
    static final int LINE_LIMIT = 128 * 1024;
    static final int CASE_LIMIT = 10_000;
    static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(64 * 1024 * 1024).build()).build());

    private JsonSupport() {
    }

    static Path path(String value, Path parent, String role) {
        if (value == null || value.isBlank() || value.startsWith("\\\\") || value.startsWith("//")
                || value.indexOf('*') >= 0 || value.indexOf('?') >= 0
                || (value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*") && !value.matches("^[A-Za-z]:[\\\\/].*"))) {
            throw new CollectionException(INPUT, role, 0, "file");
        }
        try {
            Path path = Path.of(value);
            if (parent != null && !path.isAbsolute()) {
                path = parent.resolve(path);
            }
            path = path.toAbsolutePath().normalize();
            if (path.toString().startsWith("\\\\")) {
                throw new CollectionException(INPUT, role, 0, "file");
            }
            return path;
        } catch (InvalidPathException | SecurityException e) {
            throw new CollectionException(INPUT, role, 0, "file");
        }
    }

    static byte[] read(Path path, int limit, String role) {
        try {
            if (!Files.isRegularFile(path) || path.toRealPath().toString().startsWith("\\\\")) {
                throw new CollectionException(INPUT, role, 0, "file");
            }
            try (InputStream stream = Files.newInputStream(path)) {
                return bounded(stream, limit, () -> { }, LIMIT, role);
            }
        } catch (IOException | SecurityException e) {
            throw new CollectionException(INPUT, role, 0, "file");
        }
    }

    static byte[] bounded(InputStream stream, int limit, Runnable guard,
                          CollectionException.Code code, String role) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int count = 0;
        while (true) {
            guard.run();
            int length = stream.read(buffer, 0, Math.min(buffer.length, limit - count + 1));
            guard.run();
            if (length < 0) {
                return output.toByteArray();
            }
            count += length;
            if (count > limit) {
                throw new CollectionException(code, role, 0, "bytes");
            }
            output.write(buffer, 0, length);
        }
    }

    static JsonNode parse(byte[] bytes, String role, int line) {
        return parse(bytes, role, line, INPUT, () -> { });
    }

    static JsonNode parse(byte[] bytes, String role, int line, CollectionException.Code code, Runnable guard) {
        try {
            guard.run();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            guard.run();
            try (JsonParser parser = new JsonParserDelegate(MAPPER.createParser(text)) {
                @Override
                public JsonToken nextToken() throws IOException {
                    guard.run();
                    return super.nextToken();
                }

                @Override
                public String nextFieldName() throws IOException {
                    guard.run();
                    return super.nextFieldName();
                }
            }) {
                JsonNode result = MAPPER.readTree(parser);
                if (result == null || !result.isObject() || parser.nextToken() != null) {
                    throw new CollectionException(code, role, line, "json");
                }
                guard.run();
                return result;
            }
        } catch (IOException e) {
            throw new CollectionException(code, role, line, "json");
        }
    }

    static List<JsonNode> lines(byte[] bytes, String role) {
        List<JsonNode> rows = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= bytes.length; index++) {
            if (index < bytes.length && bytes[index] != '\n' && bytes[index] != '\r') {
                if (index - start >= LINE_LIMIT) {
                    throw new CollectionException(LIMIT, role, rows.size() + 1, "bytes");
                }
                continue;
            }
            if (index == bytes.length && start == index) {
                break;
            }
            if (rows.size() >= CASE_LIMIT) {
                throw new CollectionException(LIMIT, role, rows.size() + 1, "caseId");
            }
            rows.add(parse(Arrays.copyOfRange(bytes, start, index), role, rows.size() + 1));
            if (index < bytes.length && bytes[index] == '\r' && index + 1 < bytes.length && bytes[index + 1] == '\n') {
                index++;
            }
            start = index + 1;
        }
        if (rows.isEmpty()) {
            throw new CollectionException(INPUT, role, 0, "caseId");
        }
        return rows;
    }

    static void fields(JsonNode node, Set<String> fields, String role, int line) {
        if (!node.isObject()) {
            throw new CollectionException(INPUT, role, line, "json");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!fields.contains(name)) {
                throw new CollectionException(INPUT, role, line, "json");
            }
        });
    }

    static String text(JsonNode node, String field, String role, int line) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new CollectionException(INPUT, role, line, field);
        }
        return value.textValue();
    }

    static String identifier(JsonNode node, String field, String role, int line) {
        String value = text(node, field, role, line);
        if (!identifier(value)) {
            throw new CollectionException(INPUT, role, line, field);
        }
        return value;
    }

    static boolean identifier(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    static long positive(JsonNode value, String role, int line, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new CollectionException(INPUT, role, line, field);
        }
        return value.longValue();
    }

    static int integer(JsonNode node, String field, int min, int max, int fallback, String role) {
        if (!node.has(field)) {
            return fallback;
        }
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw new CollectionException(INPUT, role, 0, field);
        }
        return value.intValue();
    }

    static boolean bool(JsonNode node, String field, boolean fallback, String role, int line) {
        if (!node.has(field)) {
            return fallback;
        }
        if (!node.path(field).isBoolean()) {
            throw new CollectionException(INPUT, role, line, field);
        }
        return node.path(field).booleanValue();
    }

    static void schema(JsonNode node, String role) {
        if (!node.has("schemaVersion") || integer(node, "schemaVersion", 1, 1, 0, role) != 1) {
            throw new CollectionException(INPUT, role, 0, "schemaVersion");
        }
    }

    static byte[] encode(Object object) {
        try {
            return MAPPER.writeValueAsBytes(object);
        } catch (IOException e) {
            throw new CollectionException(CollectionException.Code.LOCAL, "output", 0, "json");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new CollectionException(CollectionException.Code.LOCAL, "input", 0, "digest");
        }
    }

    static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.stream().sorted().forEach(key -> sorted.set(key, canonical(node.get(key))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            node.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return node;
    }
}
