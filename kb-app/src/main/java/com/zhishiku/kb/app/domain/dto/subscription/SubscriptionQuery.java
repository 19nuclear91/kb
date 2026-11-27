package com.zhishiku.kb.app.domain.dto.subscription;

import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;

import java.util.List;
import java.util.UUID;

/** 单次授权查询输入，不作为跨请求权限缓存。 */
public record SubscriptionQuery(
        long actor,
        List<Long> spaceIds,
        List<String> principalKeys,
        short clearance,
        SubscriptionKind kind,
        Long targetId,
        String pattern,
        UUID afterUuid,
        Long afterId,
        SubscriptionState state,
        int limit,
        boolean allowDisabled
) {
}
