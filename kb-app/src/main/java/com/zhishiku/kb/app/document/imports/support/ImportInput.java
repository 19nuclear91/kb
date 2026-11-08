package com.zhishiku.kb.app.document.imports.support;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FileIntent;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Manifest;
import com.zhishiku.kb.app.document.imports.service.ImportException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 输入解码及意图规范化，不依赖 HTTP、数据库或对象存储。 */
public final class ImportInput {
    public static final int MANIFEST_BYTES = 1048576;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(MANIFEST_BYTES)
                    .maxNumberLength(20).build()).build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ImportInput() {
    }

    public static ObjectNode body(InputStream input, int limit) {
        if (limit < 1 || limit > MANIFEST_BYTES) {
            throw invalid();
        }
        try {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new ImportException(ImportException.Reason.PAYLOAD_TOO_LARGE);
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var parser = JSON.createParser(text)) {
                JsonNode root = JSON.readTree(parser);
                if (!(root instanceof ObjectNode object) || parser.nextToken() != null) {
                    throw invalid();
                }
                return object;
            }
        } catch (IOException ignored) {
            throw invalid();
        }
    }

    public static Manifest manifest(ObjectNode body, int maxItems, long maxTotalBytes, long maxFileBytes) {
        fields(body, Set.of("spaceId", "securityLevel", "items"));
        long space = number(body.get("spaceId"), 1, Long.MAX_VALUE);
        short level = (short) number(body.get("securityLevel"), Short.MIN_VALUE, Short.MAX_VALUE);
        JsonNode rows = body.get("items");
        if (rows == null || !rows.isArray() || rows.isEmpty() || rows.size() > maxItems) {
            throw invalid();
        }
        List<FileIntent> files = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        long total = 0;
        for (JsonNode row : rows) {
            if (!(row instanceof ObjectNode item)) {
                throw invalid();
            }
            fields(item, Set.of("itemKey", "fileName", "title", "sizeBytes", "sha256"));
            String key = text(raw(item.get("itemKey")), 128, false, true);
            if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || !keys.add(key)) {
                throw invalid();
            }
            String fileName = fileName(raw(item.get("fileName")));
            String title = item.hasNonNull("title") ? text(raw(item.get("title")), 500, true, false) : null;
            if (title == null) {
                title = fileName;
            }
            long size = number(item.get("sizeBytes"), 1, maxFileBytes);
            String hash = raw(item.get("sha256"));
            if (!hash.matches("[0-9a-fA-F]{64}")) {
                throw invalid();
            }
            try {
                total = Math.addExact(total, size);
            } catch (ArithmeticException ignored) {
                throw invalid();
            }
            if (total > maxTotalBytes) {
                throw invalid();
            }
            files.add(new FileIntent(key, fileName, title, size, hash.toLowerCase(Locale.ROOT)));
        }
        return new Manifest(space, level, files, total, digest(List.of(space, level, files)));
    }

    public static String resolve(ObjectNode body) {
        fields(body, Set.of("requestId"));
        return requestId(raw(body.get("requestId")));
    }

    public static String requestId(String value) {
        return text(value, 256, false, true);
    }

    public static String fileName(String value) {
        String name = text(value, 255, false, true);
        if (name.length() > 255 || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw invalid();
        }
        return name;
    }

    public static long id(String value) {
        return decimal(value, false);
    }

    public static long revision(String value) {
        return decimal(value, true);
    }

    private static long decimal(String value, boolean zeroAllowed) {
        if (value == null || !value.matches(zeroAllowed ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            throw invalid();
        }
    }

    public static long number(JsonNode value, long min, long max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < min || value.longValue() > max) {
            throw invalid();
        }
        return value.longValue();
    }

    public static void fields(ObjectNode body, Set<String> allowed) {
        body.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) {
                throw invalid();
            }
        });
    }

    public static String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));
        } catch (IOException | NoSuchAlgorithmException ignored) {
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        }
    }

    private static String raw(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String text(String value, int max, boolean blankAllowed, boolean noControl) {
        if (value == null) {
            throw invalid();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw invalid();
                }
            } else if (Character.isLowSurrogate(c) || c == 0) {
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
        String normalized = value.substring(start, end);
        if (normalized.codePointCount(0, normalized.length()) > max
                || (noControl && normalized.codePoints().anyMatch(Character::isISOControl))) {
            throw invalid();
        }
        if (normalized.isEmpty()) {
            if (blankAllowed) {
                return null;
            }
            throw invalid();
        }
        return normalized;
    }

    private static boolean whitespace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    public static ImportException invalid() {
        return new ImportException(ImportException.Reason.INVALID_INPUT);
    }
}
