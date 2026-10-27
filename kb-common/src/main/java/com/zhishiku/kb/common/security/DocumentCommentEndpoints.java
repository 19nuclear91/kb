package com.zhishiku.kb.common.security;

import java.util.regex.Pattern;

/** app 与 Gateway 共用的版本评论私有响应范围；非法目标的拒绝同样不能缓存，本判断不提供认证豁免。 */
public final class DocumentCommentEndpoints {
    private static final Pattern RESOURCE = Pattern.compile("^/api/documents/[^/]+/versions/[^/]+/comments(?:/.*)?$");

    private DocumentCommentEndpoints() {
    }

    public static boolean matches(String path) {
        return path != null && RESOURCE.matcher(path).matches();
    }
}
