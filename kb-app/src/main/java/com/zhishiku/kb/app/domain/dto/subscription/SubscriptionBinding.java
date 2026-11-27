package com.zhishiku.kb.app.domain.dto.subscription;

/** 已规范化且绑定完整请求的内部值，不在接口暴露输入摘要。 */
public record SubscriptionBinding(
        long actor,
        String requestId,
        String operation,
        String digest
) {
}
