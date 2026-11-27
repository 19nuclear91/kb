package com.zhishiku.kb.app.domain.dto.subscription;

import com.zhishiku.kb.app.enums.subscription.SubscriptionState;

import java.util.UUID;

/** 已授权候选及本人已有关系的查询投影。 */
public record SubscriptionTarget(
        long targetId,
        String label,
        boolean enabled,
        UUID subscriptionId,
        UUID generationId,
        long stateVersion,
        SubscriptionState state
) {
}
