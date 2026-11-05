package com.zhishiku.kb.common.office;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/** 专用于预览网关的短期内部请求签名，不接受用户登录或供应商 JWT。 */
public final class OfficeServiceSignature {
    public static final String HEADER = "X-Office-Service-Proof";

    private OfficeServiceSignature() {
    }

    public static String sign(String secret, String path, byte[] body, Clock clock) {
        String stamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = OfficeCrypto.proof();
        return stamp + "." + nonce + "." + OfficeCrypto.hmac(secret, canonical(path, body, stamp, nonce));
    }

    public static boolean verify(String secret, String path, byte[] body, String proof, Clock clock) {
        try {
            if (secret == null || secret.length() < 32 || proof == null || proof.length() > 256) {
                return false;
            }
            String[] parts = proof.split("\\.", -1);
            if (parts.length != 3 || !parts[0].matches("[0-9]{1,12}") || !parts[1].matches("[A-Za-z0-9_-]{43}")) {
                return false;
            }
            long time = Long.parseLong(parts[0]);
            long now = clock.instant().getEpochSecond();
            return time >= now - 30 && time <= now + 5
                    && OfficeCrypto.equal(parts[2], OfficeCrypto.hmac(secret, canonical(path, body, parts[0], parts[1])));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String canonical(String path, byte[] body, String stamp, String nonce) {
        if (!path.matches("/internal/office-preview/(bootstrap|authorize|configuration)")) {
            throw new IllegalArgumentException("内部预览路径无效");
        }
        return "kb-office-preview\nPOST\n" + path + "\n" + stamp + "\n" + nonce + "\n"
                + OfficeCrypto.digest(new String(body, StandardCharsets.UTF_8));
    }
}
