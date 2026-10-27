package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.common.security.IdentityException;

import java.util.Locale;

/**
 * 身份与部门写服务共用的入参校验。
 */
final class IdentityWriteSupport {

    private IdentityWriteSupport() {
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw IdentityException.badRequest(field + "不能为空");
        }
        return value.trim();
    }

    static String maxLength(String value, String field, int max) {
        String normalized = required(value, field);
        if (normalized.length() > max) {
            throw IdentityException.badRequest(field + "长度不能超过 " + max);
        }
        return normalized;
    }

    static long version(Long version) {
        if (version == null || version < 0) {
            throw IdentityException.badRequest("version 必须为非负数");
        }
        return version;
    }

    static String requestId(String requestId) {
        return required(requestId, "requestId");
    }

    static String provider(String provider) {
        return required(provider, "provider").toLowerCase(Locale.ROOT);
    }

    static String subject(String subject) {
        return required(subject, "subject");
    }

    static long id(long id, String field) {
        if (id <= 0) throw IdentityException.badRequest(field + "必须为正数");
        return id;
    }
}
