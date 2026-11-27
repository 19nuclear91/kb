package com.zhishiku.kb.app.domain.subscription;

import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;

import java.time.Instant;

/** kb_knowledge_preference 表：本人当前统一提醒偏好，读取缺省不落库。 */
public record SubscriptionPreference(
        /** 所属用户ID，关联sys_user。 */
        long userId,
        /** PER_PUBLICATION逐次、WEEKLY每周。 */
        SubscriptionMode mode,
        /** 偏好版本，持久记录从1递增。 */
        long version,
        /** 当前频率生效的事务位置。 */
        long effectivePosition,
        /** 生效业务时间，UTC时间点。 */
        Instant effectiveAt
) {
}
