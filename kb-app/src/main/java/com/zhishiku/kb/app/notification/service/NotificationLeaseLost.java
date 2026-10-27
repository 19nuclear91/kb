package com.zhishiku.kb.app.notification.service;

/** 租约已被接管或 token 过期；抛出即回滚本批，旧执行不能推进新进度。 */
public class NotificationLeaseLost extends RuntimeException {
    public NotificationLeaseLost() {
        super("Notification lease lost", null, false, false);
    }
}
