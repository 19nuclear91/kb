package com.zhishiku.kb.common.security;

/**
 * 网关与主应用共用的精确方法／路径边界；不引入供应商依赖。
 */
public final class SsoPublicEndpoints {
    private SsoPublicEndpoints() {
    }

    public static boolean matches(String method, String path) {
        if (path == null) return false;
        if ("GET".equals(method)) return path.equals("/api/auth/sso/providers")
                || path.matches("/api/auth/sso/[A-Za-z0-9_-]{1,64}/callback");
        if ("POST".equals(method)) return path.equals("/api/auth/sso/exchange")
                || path.matches("/api/auth/sso/[A-Za-z0-9_-]{1,64}/login");
        return false;
    }
}
