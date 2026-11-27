package com.zhishiku.kb.app.domain.subscription;

import java.time.Instant;
import java.util.UUID;

/** kb_knowledge_subscription_period 表：订阅代次的固定生效与首次取消事实。 */
public record SubscriptionPeriod(
        /** 本次订阅代次UUID。 */
        UUID id,
        /** 关联稳定订阅关系UUID。 */
        UUID subscriptionId,
        /** 所属用户ID，与关系所有者一致。 */
        long userId,
        /** 生效事务位置，仅之后的发布参与匹配。 */
        long startPosition,
        /** 生效业务时间，UTC时间点。 */
        Instant startAt,
        /** 首次取消事务位置，未取消为空。 */
        Long endPosition,
        /** 首次取消业务时间，未取消为空，UTC时间点。 */
        Instant endAt
) {
}
