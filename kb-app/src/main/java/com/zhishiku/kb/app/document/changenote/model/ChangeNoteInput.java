package com.zhishiku.kb.app.document.changenote.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteException;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public record ChangeNoteInput(String text, String reason, long expectedNoteVersion) {
    public static final int BODY_LIMIT = 32768;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(BODY_LIMIT).maxNumberLength(20).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public ChangeNoteInput {
        text = normalize(text, 2000, true);
        reason = normalize(reason, 2000, true);
        if (expectedNoteVersion < 0) {
            throw invalid();
        }
    }

    public static ChangeNoteInput parse(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(BODY_LIMIT + 1);
            if (bytes.length > BODY_LIMIT) {
                throw new ChangeNoteException(Reason.PAYLOAD_TOO_LARGE);
            }
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            try (var parser = JSON.createParser(raw)) {
                JsonNode object = JSON.readTree(parser);
                Set<String> fields = Set.of("text", "reason", "expectedNoteVersion");
                if (object == null || !object.isObject() || object.size() != 3 || parser.nextToken() != null) {
                    throw invalid();
                }
                object.fieldNames().forEachRemaining(key -> {
                    if (!fields.contains(key)) {
                        throw invalid();
                    }
                });
                JsonNode version = object.get("expectedNoteVersion");
                if (version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) {
                    throw invalid();
                }
                return new ChangeNoteInput(nullableText(object.get("text")), nullableText(object.get("reason")), version.longValue());
            }
        } catch (ChangeNoteException domain) {
            throw domain;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    public static String requestId(String raw) {
        if (raw == null) {
            throw invalid();
        }
        return normalize(raw, 128, false);
    }

    private static String nullableText(JsonNode node) {
        if (node == null) {
            throw invalid();
        }
        if (node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid();
        }
        return node.textValue();
    }

    private static String normalize(String value, int maximum, boolean multiline) {
        if (value == null) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    throw invalid();
                }
            } else if (Character.isLowSurrogate(character)
                    || (Character.isISOControl(character) && !(multiline && (character == '\n' || character == '\r' || character == '\t')))) {
                throw invalid();
            }
        }
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && whitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        value = value.substring(start, end);
        int size = value.codePointCount(0, value.length());
        if (size < 1 || size > maximum) {
            throw invalid();
        }
        return value;
    }

    private static boolean whitespace(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    public static ChangeNoteException invalid() {
        return new ChangeNoteException(Reason.INVALID_INPUT);
    }
}
