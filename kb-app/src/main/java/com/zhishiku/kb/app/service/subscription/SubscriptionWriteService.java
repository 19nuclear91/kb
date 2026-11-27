package com.zhishiku.kb.app.service.subscription;

import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;

import java.util.UUID;

/** 本人订阅与偏好变更，所有请求绑定当前用户和原意图。 */
public interface SubscriptionWriteService {
    SubscriptionViews.Receipt subscribe(long actor, String requestId, SubscriptionKind kind, long targetId, long expectedVersion);

    SubscriptionViews.Receipt cancel(long actor, String requestId, UUID subscriptionId, UUID generationId, long expectedVersion);

    SubscriptionViews.Receipt preference(long actor, String requestId, SubscriptionMode mode, long expectedVersion);
}
