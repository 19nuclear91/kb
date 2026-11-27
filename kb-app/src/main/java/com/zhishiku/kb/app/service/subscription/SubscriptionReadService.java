package com.zhishiku.kb.app.service.subscription;

import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;

/** 本人订阅管理读取，目标内容按当前资格投影。 */
public interface SubscriptionReadService {
    SubscriptionViews.Page<SubscriptionViews.Item> list(long actor, SubscriptionKind kind, SubscriptionState state, int limit, String cursor);

    SubscriptionViews.Page<SubscriptionViews.Candidate> candidates(long actor, SubscriptionKind kind, String query, int limit, String cursor);

    SubscriptionViews.Candidate target(long actor, SubscriptionKind kind, long targetId);

    SubscriptionViews.Preference preference(long actor);

    SubscriptionViews.ReceiptLookup receipt(long actor, String requestId);
}
