package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 审核送审与结果通知的当前可见性复核。SQL 已用 UserNotificationMapper.visible 过滤，本类按投影事实
 * 独立再判一次，两者不一致时列表读取安全失败；未读数只执行 SQL，不经本类。
 *
 * <p>待审核：SQL 的 actionable 与审核工作台待办共用 pendingReviewer 片段；这里另行核对同一轮仍
 * IN_REVIEW、未搬迁、未发布且收件人不是送审者或上传者。放宽该片段时须同步修改本类。
 * 审核结果：本人为本轮送审者、结果未变，当前可交互读取该固定版本；edit-only 送审者只能
 * 从本人审核工作台读取最小状态，通知不借此泄露文档或意见。
 */
@Component
public class ReviewNotificationTargets implements NotificationTargetResolver {
    private static final Set<NotificationType> TYPES = Set.of(NotificationType.REVIEW_SUBMITTED,
            NotificationType.REVIEW_APPROVED, NotificationType.REVIEW_REJECTED);

    @Override
    public Set<NotificationType> types() {
        return TYPES;
    }

    @Override
    public Optional<NotificationTarget> resolve(NotificationRow row) {
        var type = NotificationType.valueOf(row.type());
        if (!TYPES.contains(type) || row.reviewId() == null || row.roundNo() == null || row.roundNo() <= 0)
            throw new IllegalStateException("Notification facts do not match review target");
        return visible(type, row)
                ? Optional.of(NotificationTarget.review(row.documentId(), row.versionId(), row.reviewId()))
                : Optional.empty();
    }

    static boolean visible(NotificationType type, NotificationRow row) {
        if (!row.live() || !row.cleared()) return false;
        long recipient = row.recipientUserId();
        return switch (type) {
            case REVIEW_SUBMITTED -> row.actionable() && "IN_REVIEW".equals(row.roundState()) && row.roundInSpace()
                    && "REVIEW_REQUIRED".equals(row.publicationPolicy()) && "NONE".equals(row.publicationEvidence())
                    && row.roundSubmittedBy() != null && row.roundSubmittedBy() != recipient
                    && row.uploaderUserId() != null && row.uploaderUserId() != recipient;
            case REVIEW_APPROVED, REVIEW_REJECTED -> row.viewable()
                    && Objects.equals(row.roundSubmittedBy(), recipient)
                    && type.name().equals("REVIEW_" + row.roundState());
            default -> false;
        };
    }
}
