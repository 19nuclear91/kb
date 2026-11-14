package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 问题目标不读取资料或正文，只根据同源问题授权及当前工作身份复核。 */
@Component
public class GapNotificationTargets implements NotificationTargetResolver {
    @Override
    public Set<NotificationType> types() {
        return Arrays.stream(NotificationType.values()).filter(NotificationType::knowledgeGap).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Optional<NotificationTarget> resolve(NotificationRow row) {
        var fact = row.gap();
        if (fact == null || row.documentId() != null || row.versionId() != null || row.reviewId() != null || row.commentId() != null
                || !row.type().equals(fact.eventKind())) {
            throw new IllegalArgumentException("Inconsistent gap notification source");
        }
        if (!Boolean.TRUE.equals(fact.visible()) || !Boolean.TRUE.equals(fact.workCurrent()) || !fact.currentWork(row.recipientUserId())) {
            return Optional.empty();
        }
        return Optional.of(NotificationTarget.knowledgeGap(fact.gapId()));
    }
}
