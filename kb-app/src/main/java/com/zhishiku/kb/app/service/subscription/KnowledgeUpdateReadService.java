package com.zhishiku.kb.app.service.subscription;

import com.zhishiku.kb.app.domain.vo.subscription.KnowledgeViews;
import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;

import java.time.LocalDate;
import java.util.UUID;

/** 本人固定更新与周报读取，不生成提醒或更改已读状态。 */
public interface KnowledgeUpdateReadService {
    SubscriptionViews.Page<KnowledgeViews.Update> updates(long actor, LocalDate week, int limit, String cursor);

    KnowledgeViews.Detail detail(long actor, UUID updateId);

    SubscriptionViews.Page<KnowledgeViews.Digest> digests(long actor, int limit, String cursor);

    KnowledgeViews.DigestDetail digest(long actor, UUID digestId, int limit, String cursor);

    SubscriptionViews.Page<KnowledgeViews.Update> members(long actor, UUID digestId, long documentId, int limit, String cursor);
}
