package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.notification.service.NotificationTargetResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/** 对共享授权投影再次检查来源身份，只生成本人的最小站内目标。 */
@Component
public class KnowledgeNotificationTargets implements NotificationTargetResolver {
    @Override
    public Set<NotificationType> types() {
        return Set.of(NotificationType.SUBSCRIBED_VERSION_PUBLISHED, NotificationType.KNOWLEDGE_WEEKLY_DIGEST);
    }

    @Override
    public Optional<NotificationTarget> resolve(NotificationRow row) {
        var fact = row.knowledge();
        if (fact == null || fact.deliveryId() == null || fact.userId() == null || fact.userId() != row.recipientUserId()
                || !"NOTIFIED".equals(fact.status()) || !Boolean.TRUE.equals(fact.visible())
                || row.gap() != null || row.documentId() != null || row.versionId() != null || row.reviewId() != null || row.commentId() != null) {
            return Optional.empty();
        }
        if ("PER_PUBLICATION".equals(fact.kind()) && fact.updateId() != null && fact.weekStart() == null
                && NotificationType.SUBSCRIBED_VERSION_PUBLISHED.name().equals(row.type())) {
            return Optional.of(NotificationTarget.knowledgeUpdate(fact.updateId()));
        }
        if ("WEEKLY".equals(fact.kind()) && fact.updateId() == null && fact.weekStart() != null
                && fact.weekStart().getDayOfWeek() == java.time.DayOfWeek.MONDAY
                && NotificationType.KNOWLEDGE_WEEKLY_DIGEST.name().equals(row.type())) {
            return Optional.of(NotificationTarget.knowledgeDigest(fact.deliveryId()));
        }
        return Optional.empty();
    }
}
