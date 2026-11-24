package com.zhishiku.kb.app.search.assistance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** 有界签名信封，各消费者另行严格核对自己的载荷字段与完整绑定。 */
@Component
public final class SignedSearchContext {
    public static final int TOKEN_LIMIT = 4096;
    public static final int PAYLOAD_LIMIT = 2048;
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(PAYLOAD_LIMIT).maxNumberLength(20).build())
            .build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final SearchContextKeys keys;

    public enum Purpose {
        SEARCH_SELECTION, TERM_DIRECTORY, CORRECTION_DIRECTORY, TERM_MEANINGS
    }

    /**
     * 经过 MAC 与用途核验的载荷，读取返回副本，字符串投影不包含内容。
     * @param keyId 信封实际验证密钥标识
     * @param payload 本次严格 JSON 对象
     */
    public record Opened(String keyId, ObjectNode payload) {
        public Opened {
            payload = payload.deepCopy();
        }

        @Override
        public ObjectNode payload() {
            return payload.deepCopy();
        }

        @Override
        public String toString() {
            return "SignedSearchContext.Opened[redacted]";
        }
    }

    public SignedSearchContext(SearchContextKeys keys) {
        this.keys = keys;
    }

    public String seal(Purpose purpose, ObjectNode value) {
        String keyId = keys.currentKeyId();
        ObjectNode payload = value.deepCopy().put("purpose", purpose.name());
        try {
            byte[] bytes = JSON.writeValueAsBytes(payload);
            if (bytes.length > PAYLOAD_LIMIT) {
                throw ContextEncoding.unavailable();
            }
            String prefix = "1." + keyId + "." + BASE64.encodeToString(bytes);
            String result = prefix + "." + BASE64.encodeToString(envelopeMac(keyId, purpose, prefix));
            if (result.length() > TOKEN_LIMIT) {
                throw ContextEncoding.unavailable();
            }
            return result;
        } catch (IOException unavailable) {
            throw ContextEncoding.unavailable();
        }
    }

    public Opened open(String token, Purpose purpose) {
        if (token == null || token.isEmpty() || token.length() > TOKEN_LIMIT) {
            throw ContextEncoding.invalid();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("1") || !SearchContextKeys.validId(parts[1])) {
            throw ContextEncoding.invalid();
        }
        byte[] payload = decode(parts[2], PAYLOAD_LIMIT);
        byte[] signature = decode(parts[3], 32);
        if (signature.length != 32) {
            throw ContextEncoding.invalid();
        }
        String prefix = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(signature, envelopeMac(parts[1], purpose, prefix))) {
            throw ContextEncoding.invalid();
        }
        try (JsonParser parser = JSON.createParser(payload)) {
            JsonNode root = JSON.readTree(parser);
            if (!(root instanceof ObjectNode object) || parser.nextToken() != null
                    || !object.path("purpose").isTextual() || !object.path("purpose").textValue().equals(purpose.name())) {
                throw ContextEncoding.invalid();
            }
            return new Opened(parts[1], object);
        } catch (IOException invalid) {
            throw ContextEncoding.invalid();
        }
    }

    private byte[] envelopeMac(String keyId, Purpose purpose, String prefix) {
        return ContextEncoding.mac(keys.verificationKey(keyId), "search-context-envelope-v1",
                purpose.name().getBytes(StandardCharsets.US_ASCII), prefix.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] decode(String value, int maxBytes) {
        if (value == null || value.isEmpty() || value.length() > (maxBytes * 4 + 2) / 3
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw ContextEncoding.invalid();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length > maxBytes || !BASE64.encodeToString(decoded).equals(value)) {
                throw ContextEncoding.invalid();
            }
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw ContextEncoding.invalid();
        }
    }
}
