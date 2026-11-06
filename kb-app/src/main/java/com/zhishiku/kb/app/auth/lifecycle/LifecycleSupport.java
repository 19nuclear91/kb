package com.zhishiku.kb.app.auth.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;

public final class LifecycleSupport {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private LifecycleSupport() {
    }

    public static String text(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.trim().length() > limit) {
            throw IdentityException.badRequest(field + "格式无效");
        }
        return value.trim();
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("生命周期事实无法编码");
        }
    }

    public static <T> T read(String value, Class<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (Exception invalid) {
            throw new IllegalStateException("生命周期事实无法解码");
        }
    }

    public static String digest(Object value) {
        return RequestSignature.digest(json(value));
    }

    public static IdentityException stale() {
        return IdentityException.conflict(IdentityConflictReason.LIFECYCLE_CONFIRMATION_STALE,
                "核对事实已变化，请重新核验");
    }
}
