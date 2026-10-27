package com.zhishiku.kb.common.conversation;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * 绑定实际请求的 JDK HMAC；异常固定且不携带凭据。
 */
public final class RequestSignature {
    private RequestSignature() {
    }

    /**
     * 服务端原子去重边界；异常必须失败关闭。
     */
    public interface NonceStore {
        boolean claim(String caller, String nonce, long ttlSeconds);
    }

    /**
     * 签名头。
     *
     * @param caller    服务身份
     * @param timestamp UTC 秒
     * @param nonce     随机请求身份
     * @param signature 请求签名
     */
    public record Headers(String caller, long timestamp, String nonce, String signature) {
    }

    public static Headers sign(String secret, String caller, String method, String path,
                               byte[] body, String token, Clock clock) {
        long timestamp = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        return new Headers(caller, timestamp, nonce,
                hmac(secret, canonical(caller, method, path, timestamp, nonce, body, token)));
    }

    public static void verify(String secret, String expectedCaller, String method, String path,
                              byte[] body, String token, Headers headers, Clock clock,
                              long windowSeconds, long nonceTtlSeconds, NonceStore nonces) {
        try {
            if (headers == null || !expectedCaller.equals(headers.caller())
                    || headers.nonce() == null || !headers.nonce().matches("[a-zA-Z0-9-]{16,64}")
                    || headers.timestamp() < clock.instant().getEpochSecond() - windowSeconds
                    || headers.timestamp() > clock.instant().getEpochSecond() + windowSeconds) {
                throw new SecurityException();
            }
            String expected = hmac(secret, canonical(headers.caller(), method, path,
                    headers.timestamp(), headers.nonce(), body, token));
            if (!constantEquals(expected, headers.signature())
                    || !nonces.claim(headers.caller(), headers.nonce(), nonceTtlSeconds)) {
                throw new SecurityException();
            }
        } catch (RuntimeException error) {
            throw new SecurityException("内部请求身份无效");
        }
    }

    private static String canonical(String caller, String method, String path, long time,
                                    String nonce, byte[] body, String token) {
        if (caller == null || !caller.matches("kb-(app|ai)") || method == null
                || !method.matches("[A-Z]+") || path == null || !path.startsWith("/internal/")
                || path.contains("\\n") || path.contains("\\r") || path.contains("?")) {
            throw new SecurityException("内部请求身份无效");
        }
        return caller + "\\n" + method.toUpperCase(Locale.ROOT) + "\\n" + path + "\\n" + time
                + "\\n" + nonce + "\\n" + sha256(body) + "\\n" + digest(token == null ? "" : token);
    }

    public static String digest(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }

    public static boolean constantEquals(String first, String second) {
        if (first == null || second == null) return false;
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new SecurityException("内部请求身份无效");
        }
    }
}

