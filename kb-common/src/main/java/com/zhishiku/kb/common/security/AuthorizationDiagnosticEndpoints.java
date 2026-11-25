package com.zhishiku.kb.common.security;

/** 包含未知子路由，保证提前失败也经过私有响应策略。 */
public final class AuthorizationDiagnosticEndpoints {
    private AuthorizationDiagnosticEndpoints() {
    }

    public static boolean matches(String path) {
        return path != null && path.matches("/api/admin/(spaces|documents)/[^/]+/authorization-diagnostics(?:/.*)?");
    }
}
