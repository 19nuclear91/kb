package com.zhishiku.kb.common.security;

/** 搜索响应保护的精确范围，不提供认证豁免。 */
public final class SearchEndpoints {
    private SearchEndpoints() {
    }

    public static boolean matches(String path) {
        return "/api/search".equals(path) || "/api/search/categories".equals(path) || "/api/search/tags".equals(path);
    }
}
