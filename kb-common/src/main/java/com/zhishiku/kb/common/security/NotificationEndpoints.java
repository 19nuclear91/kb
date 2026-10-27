package com.zhishiku.kb.common.security;

/** app 与 Gateway 共用的本人通知响应策略范围；本判断不提供认证豁免。 */
public final class NotificationEndpoints {
    private NotificationEndpoints() {
    }

    public static boolean matches(String path) {
        return path != null && (path.equals("/api/notifications") || path.startsWith("/api/notifications/"));
    }
}
