package com.zhishiku.notifier;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

final class BoundedJson {
    static final int REQUEST_BYTES = 256 * 1024;
    static final int RESPONSE_BYTES = 16 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(8192).maxNameLength(8192).maxNumberLength(128).build()).build());

    private BoundedJson() {
    }

    static byte[] read(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new Rejection(413, Rejection.Reason.too_large);
        }
        return bytes;
    }

    static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static JsonNode parse(byte[] bytes, int limit) {
        if (bytes.length > limit) {
            throw new Rejection(413, Rejection.Reason.too_large);
        }
        try (JsonParser parser = MAPPER.createParser(utf8(bytes))) {
            JsonNode node = MAPPER.readTree(parser);
            if (node == null || parser.nextToken() != null) {
                throw Rejection.invalid();
            }
            validateStrings(node);
            return node;
        } catch (StreamConstraintsException exception) {
            throw new Rejection(413, Rejection.Reason.too_large);
        } catch (IOException exception) {
            throw Rejection.invalid();
        }
    }

    static byte[] encode(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException exception) {
            throw new Rejection(503, Rejection.Reason.internal);
        }
    }

    private static void validateStrings(JsonNode node) {
        if (node.isTextual()) {
            validateString(node.textValue());
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                validateString(field.getKey());
                validateStrings(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                validateStrings(child);
            }
        }
    }

    static void validateString(String value) {
        if (value.codePointCount(0, value.length()) > 4096) {
            throw new Rejection(413, Rejection.Reason.too_large);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw Rejection.invalid();
                }
            } else if (Character.isLowSurrogate(character)) {
                throw Rejection.invalid();
            }
        }
    }

    static String text(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw Rejection.invalid();
        }
        return value.textValue();
    }

    static JsonNode object(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isObject()) {
            throw Rejection.invalid();
        }
        return value;
    }
}
