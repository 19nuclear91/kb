package com.zhishiku.kb.common.security;

/** 词典管理的固定路径边界，不负责身份或功能资格判定。 */
public final class SearchTermEndpoints {
    public static final String PREFIX = "/api/admin/search-terms";

    private SearchTermEndpoints() {
    }

    public static boolean matches(String path) {
        return path != null && (path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }
}
