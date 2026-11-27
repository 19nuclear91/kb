package com.zhishiku.kb.common.security;

import java.util.List;

/** 本人知识订阅及更新的私有业务路径，不包含内部调度或管理入口。 */
public final class KnowledgeSubscriptionEndpoints {
    private static final List<String> ROOTS = List.of("/api/knowledge-subscriptions", "/api/knowledge-subscription-preferences",
            "/api/knowledge-updates", "/api/knowledge-digests");

    private KnowledgeSubscriptionEndpoints() {
    }

    public static boolean matches(String path) {
        return path != null && ROOTS.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
    }
}
