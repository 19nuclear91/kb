package com.zhishiku.kb.common.security;

import java.util.regex.Pattern;

/** app 与 Gateway 共用的审核隐私响应范围，非法目标也不能缓存。 */
public final class DocumentReviewEndpoints {
    private static final Pattern RESOURCE = Pattern.compile(
            "^/api/admin/spaces/[^/]+/publication-policy/?$"
                    + "|^/api/documents/[^/]+/versions/[^/]+/(?:review|reviews(?:/[^/]+/(?:approve|reject|withdraw))?|index-refresh)/?$");

    private DocumentReviewEndpoints() {
    }

    public static boolean matches(String path) {
        return path != null && (path.equals("/api/reviews") || path.startsWith("/api/reviews/")
                || RESOURCE.matcher(path).matches());
    }
}
