package com.zhishiku.kb.app.search.assistance;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;

/** 长度前缀的二进制绑定，不使用字符串拼接或普通查询摘要。 */
final class ContextEncoding {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream output = new DataOutputStream(bytes);

    ContextEncoding text(String value) {
        return data(value == null ? null : utf8(value));
    }

    ContextEncoding data(byte[] value) {
        try {
            output.writeInt(value == null ? -1 : value.length);
            if (value != null) {
                output.write(value);
            }
            return this;
        } catch (IOException impossible) {
            throw unavailable();
        }
    }

    ContextEncoding number(long value) {
        try {
            output.writeLong(value);
            return this;
        } catch (IOException impossible) {
            throw unavailable();
        }
    }

    ContextEncoding flag(boolean value) {
        try {
            output.writeBoolean(value);
            return this;
        } catch (IOException impossible) {
            throw unavailable();
        }
    }

    ContextEncoding optionalId(Long value) {
        flag(value != null);
        if (value != null) {
            number(value);
        }
        return this;
    }

    ContextEncoding time(OffsetDateTime value) {
        flag(value != null);
        if (value != null) {
            number(value.toInstant().getEpochSecond()).number(value.toInstant().getNano());
        }
        return this;
    }

    byte[] bytes() {
        return bytes.toByteArray();
    }

    static byte[] mac(SecretKey key, String purpose, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            for (byte[] part : joinPurpose(purpose, parts)) {
                mac.update(ByteBuffer.allocate(4).putInt(part.length).array());
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException unavailable) {
            throw unavailable();
        }
    }

    private static byte[][] joinPurpose(String purpose, byte[][] parts) {
        byte[][] result = new byte[parts.length + 1][];
        result[0] = utf8(purpose);
        System.arraycopy(parts, 0, result, 1, parts.length);
        return result;
    }

    static byte[] utf8(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw invalid();
                }
            } else if (Character.isLowSurrogate(c)) {
                throw invalid();
            }
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static SearchContextFailure invalid() {
        return new SearchContextFailure(SearchContextFailure.Kind.INVALID_INPUT);
    }

    static SearchContextFailure unavailable() {
        return new SearchContextFailure(SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE);
    }

    @Override
    public String toString() {
        return "ContextEncoding[redacted]";
    }
}
