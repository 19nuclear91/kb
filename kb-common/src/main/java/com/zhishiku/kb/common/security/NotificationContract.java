package com.zhishiku.kb.common.security;

import com.zhishiku.kb.common.api.ApiException;

import java.util.List;

/** 通知读取能力声明；缺省保持旧契约，声明不会授予资源或本人归属资格。 */
public enum NotificationContract {
    LEGACY,
    KNOWLEDGE_GAPS_V1;

    public static final String HEADER = "X-Kb-Notification-Contract";

    public static NotificationContract parse(List<String> values) {
        if (values == null || values.isEmpty()) {
            return LEGACY;
        }
        if (values.size() != 1 || !KNOWLEDGE_GAPS_V1.name().equals(values.getFirst())) {
            throw ApiException.badRequest("通知能力声明无效");
        }
        return KNOWLEDGE_GAPS_V1;
    }
}
