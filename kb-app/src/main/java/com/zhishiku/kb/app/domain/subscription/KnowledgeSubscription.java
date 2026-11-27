package com.zhishiku.kb.app.domain.subscription;

import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;

import java.time.Instant;
import java.util.UUID;

/** kb_knowledge_subscription 表：本人关注目标的稳定关系与当前代次。 */
public record KnowledgeSubscription(
        /** 稳定订阅关系UUID，重新订阅时保持。 */
        UUID id,
        /** 所属用户ID，关联sys_user。 */
        long userId,
        /** DOCUMENT文档、SPACE空间、TAG标签。 */
        SubscriptionKind kind,
        /** 对应目标类型的稳定资源ID。 */
        long targetId,
        /** ACTIVE有效、CANCELLED已取消。 */
        SubscriptionState state,
        /** 当前或最后一次订阅代次UUID。 */
        UUID generationId,
        /** 关系并发版本，从1开始递增。 */
        long stateVersion,
        /** 首次创建业务时间，UTC时间点。 */
        Instant createdAt,
        /** 最近变更业务时间，UTC时间点。 */
        Instant updatedAt
) {
}
