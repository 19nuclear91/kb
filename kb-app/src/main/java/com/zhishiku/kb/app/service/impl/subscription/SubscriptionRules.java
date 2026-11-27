package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 订阅命令与查询的确定性输入规则，不访问存储或外部服务。 */
public final class SubscriptionRules {
    private SubscriptionRules() {
    }

    public static String text(String value, int minimum, int maximum) {
        if (value == null) {
            throw SubscriptionFailure.invalid();
        }
        String result = value;
        int length = result.codePointCount(0, result.length());
        if (length < minimum || length > maximum) {
            throw SubscriptionFailure.invalid();
        }
        for (int offset = 0; offset < result.length();) {
            int point = result.codePointAt(offset);
            if (Character.isISOControl(point) || (point >= 0xD800 && point <= 0xDFFF)) {
                throw SubscriptionFailure.invalid();
            }
            offset += Character.charCount(point);
        }
        return result;
    }

    public static long number(String value, long minimum) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) {
            throw SubscriptionFailure.invalid();
        }
        try {
            long number = Long.parseLong(value);
            if (number < minimum) {
                throw SubscriptionFailure.invalid();
            }
            return number;
        } catch (NumberFormatException ignored) {
            throw SubscriptionFailure.invalid();
        }
    }

    public static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw SubscriptionFailure.invalid();
        }
        return UUID.fromString(value);
    }

    public static int limit(int value) {
        if (value < 1 || value > 100) {
            throw SubscriptionFailure.invalid();
        }
        return value;
    }

    public static String pattern(String value) {
        String text = text(value == null ? "" : value, 0, 128).strip();
        if (text.isEmpty()) {
            return null;
        }
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public static void changed(int affected) {
        if (affected != 1) {
            throw SubscriptionFailure.unavailable();
        }
    }
}
