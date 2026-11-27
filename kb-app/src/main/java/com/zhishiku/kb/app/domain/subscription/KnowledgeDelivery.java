package com.zhishiku.kb.app.domain.subscription;

import com.zhishiku.kb.app.enums.subscription.DeliveryState;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** kb_knowledge_delivery 表的来源和终态投影，租约由执行仓储单独读取。 */
public record KnowledgeDelivery(
        /** 提醒工作UUID，周报也以此作为digestId。 */
        UUID id,
        /** 提醒所属用户ID，必须大于零。 */
        long userId,
        /** PER_PUBLICATION逐次、WEEKLY每周。 */
        SubscriptionMode kind,
        /** 逐次提醒的更新UUID，周报为空。 */
        UUID updateId,
        /** 周报所属周一日期，逐次为空。 */
        LocalDate weekStart,
        /** PENDING待处理、NOTIFIED已生成、NO_ELIGIBLE无合格项目。 */
        DeliveryState status,
        /** 首次终结事务位置，待处理为空。 */
        Long dispatchPosition,
        /** 首次终结时间，UTC时间点，待处理为空。 */
        Instant dispatchedAt
) {
    public KnowledgeDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        if (userId <= 0 || (kind == SubscriptionMode.PER_PUBLICATION
                ? updateId == null || weekStart != null
                : updateId != null || weekStart == null || weekStart.getDayOfWeek().getValue() != 1)) {
            throw new IllegalArgumentException("Invalid delivery source");
        }
        if ((dispatchPosition == null) != (dispatchedAt == null)
                || (status == DeliveryState.PENDING) != (dispatchPosition == null)
                || (dispatchPosition != null && dispatchPosition <= 0)) {
            throw new IllegalArgumentException("Invalid delivery outcome");
        }
    }
}
