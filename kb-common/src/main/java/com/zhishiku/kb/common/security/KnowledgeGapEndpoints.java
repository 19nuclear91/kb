package com.zhishiku.kb.common.security;

/** 新问题 API 的保密响应范围；包含非法子路径，不提供认证或功能资格豁免。 */
public final class KnowledgeGapEndpoints {
    private KnowledgeGapEndpoints() {
    }

    public static boolean matches(String path) {
        return path != null && (path.equals("/api/knowledge-gaps") || path.startsWith("/api/knowledge-gaps/")
                || path.equals("/api/admin/knowledge-gaps") || path.startsWith("/api/admin/knowledge-gaps/"));
    }
}
