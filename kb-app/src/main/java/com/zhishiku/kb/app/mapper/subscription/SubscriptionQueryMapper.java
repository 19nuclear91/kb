package com.zhishiku.kb.app.mapper.subscription;

import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionQuery;
import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionTarget;
import com.zhishiku.kb.app.domain.subscription.KnowledgeSubscription;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 订阅候选复用文档模块同源授权片段，所有分页都在服务端有界执行。 */
@Mapper
public interface SubscriptionQueryMapper {
    List<SubscriptionTarget> candidates(SubscriptionQuery query);

    SubscriptionTarget target(SubscriptionQuery query);

    List<KnowledgeSubscription> subscriptions(SubscriptionQuery query);

    Long lockTarget(
            @Param("kind")
            SubscriptionKind kind,
            @Param("targetId")
            long targetId
    );
}
