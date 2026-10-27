package com.zhishiku.kb.common.security;

/** 收藏响应策略的路径范围；本判断不提供认证豁免。 */
public final class DocumentFavoriteEndpoints {
    private DocumentFavoriteEndpoints() {
    }

    public static boolean matches(String path) {
        return path != null && (path.equals("/api/documents/favorites")
                || path.equals("/api/documents/favorites/status")
                || path.matches("/api/documents/[^/]+/favorite"));
    }
}
