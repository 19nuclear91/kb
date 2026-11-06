package com.zhishiku.kb.common.security;

import java.util.Set;

/** 所有服务共享方法与路径的精确认证例外，不能将内部路由加入此表。 */
public final class AuthenticationEndpoints {
    private static final Set<String> PUBLIC = Set.of("POST /api/auth/login", "POST /api/auth/dev-login");
    private static final Set<String> PASSWORD = Set.of("POST /api/auth/logout", "GET /api/auth/me",
            "POST /api/auth/password");

    private AuthenticationEndpoints() {
    }

    public static boolean isPublic(String method, String path) {
        return PUBLIC.contains(method + " " + path) || SsoPublicEndpoints.matches(method, path);
    }

    public static boolean allowsPasswordChange(String method, String path) {
        return isPublic(method, path) || PASSWORD.contains(method + " " + path);
    }
}
