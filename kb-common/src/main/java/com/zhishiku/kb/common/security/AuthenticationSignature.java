package com.zhishiku.kb.common.security;

import com.zhishiku.kb.common.conversation.RequestSignature;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** 独立认证协议，不共用会话功能开关、密钥或 nonce 命名空间。 */
public final class AuthenticationSignature {
    public static final String PATH = "/internal/auth/check";
    public static final String STOP_PATH = "/internal/auth/output-stopped";
    public static final int STOP_MAX_BYTES = 2048;
    public static final String CALLER = "X-Auth-Caller";
    public static final String TIMESTAMP = "X-Auth-Timestamp";
    public static final String NONCE = "X-Auth-Nonce";
    public static final String SIGNATURE = "X-Auth-Signature";
    public static final long WINDOW_SECONDS = 30;
    public static final long NONCE_SECONDS = 90;
    private static final Set<String> CALLERS = Set.of("kb-gateway", "kb-ai");

    public interface NonceStore {
        boolean claim(String caller, String nonce, long ttlSeconds);
    }

    public record Headers(String caller, long timestamp, String nonce, String signature) {
        @Override
        public String toString() {
            return "AuthenticationHeaders[redacted]";
        }
    }

    private AuthenticationSignature() {
    }

    public static boolean configured(String secret) {
        return secret != null && !secret.isBlank() && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    public static Headers sign(String secret, String caller, String token, Clock clock) {
        long timestamp = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        return new Headers(caller, timestamp, nonce, mac(secret, canonical(caller, timestamp, nonce, token)));
    }

    public static Headers signStop(String secret, byte[] body, Clock clock) {
        long timestamp = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        return new Headers("kb-ai", timestamp, nonce, mac(secret, stopCanonical(timestamp, nonce, body)));
    }

    public static void verifyStop(String secret, String method, String path, byte[] body, Headers headers,
                                  Clock clock, NonceStore nonces) {
        if (!"POST".equals(method) || !STOP_PATH.equals(path) || headers == null || !"kb-ai".equals(headers.caller())
                || headers.nonce() == null || !headers.nonce().matches("[a-zA-Z0-9-]{16,64}")
                || headers.timestamp() < clock.instant().getEpochSecond() - WINDOW_SECONDS
                || headers.timestamp() > clock.instant().getEpochSecond() + WINDOW_SECONDS
                || !RequestSignature.constantEquals(mac(secret, stopCanonical(headers.timestamp(), headers.nonce(), body)), headers.signature())
                || !nonces.claim("kb-ai", headers.nonce(), NONCE_SECONDS)) {
            throw rejected();
        }
    }

    private static String stopCanonical(long timestamp, String nonce, byte[] body) {
        if (body == null || body.length == 0 || body.length > STOP_MAX_BYTES) {
            throw rejected();
        }
        return "kb-auth-output-stop-v1\nkb-ai\nPOST\n" + STOP_PATH + "\n" + timestamp + "\n" + nonce
                + "\n" + RequestSignature.sha256(body);
    }

    public static void verify(String secret, String method, String path, byte[] body, String token,
                              Headers headers, Clock clock, NonceStore nonces) {
        if (!"POST".equals(method) || !PATH.equals(path) || (body != null && body.length != 0)
                || headers == null || !CALLERS.contains(headers.caller()) || headers.nonce() == null
                || !headers.nonce().matches("[a-zA-Z0-9-]{16,64}")
                || headers.timestamp() < clock.instant().getEpochSecond() - WINDOW_SECONDS
                || headers.timestamp() > clock.instant().getEpochSecond() + WINDOW_SECONDS) {
            throw rejected();
        }
        String expected = mac(secret, canonical(headers.caller(), headers.timestamp(), headers.nonce(), token));
        if (!RequestSignature.constantEquals(expected, headers.signature())) {
            throw rejected();
        }
        // 存储故障原样向服务边界传播为 503，不能降级为通过或“已查无此 nonce”。
        if (!nonces.claim(headers.caller(), headers.nonce(), NONCE_SECONDS)) {
            throw rejected();
        }
    }

    private static String canonical(String caller, long timestamp, String nonce, String token) {
        if (!CALLERS.contains(caller) || token == null || token.isBlank() || token.length() > 4096) {
            throw rejected();
        }
        return "kb-auth-v1\n" + caller + "\nPOST\n" + PATH + "\nCHECK\n" + timestamp + "\n" + nonce
                + "\n" + RequestSignature.digest(token);
    }

    private static String mac(String secret, String value) {
        if (!configured(secret)) {
            throw new IllegalStateException("内部认证密钥未配置");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failed) {
            throw new IllegalStateException("内部认证签名不可用");
        }
    }

    private static SecurityException rejected() {
        return new SecurityException("内部认证请求无效");
    }
}
