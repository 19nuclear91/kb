package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 评论回复通知的当前可见性复核。SQL 的 visible 片段已用评论目标发布证据与评论模块 liveReply 片段过滤；
 * 本类按投影事实独立再判一次，不一致时列表读取安全失败。只依赖投影，不引用评论模块 Java 或 Mapper。
 *
 * <p>回复通知定位固定版本中的那条回复：当前 view、密级、未删除未归档、版本有发布证据，且回复未删除。
 * 根评论已删不隐藏合法回复；回复被删后不再显示，不暴露删除前内容。
 */
@Component
public class CommentNotificationTargets implements NotificationTargetResolver {
    private static final Set<NotificationType> TYPES = Set.of(NotificationType.COMMENT_REPLIED);
    private static final Set<String> EVIDENCE = Set.of("PUBLISHED", "OBSERVED_CURRENT");

    @Override
    public Set<NotificationType> types() {
        return TYPES;
    }

    @Override
    public Optional<NotificationTarget> resolve(NotificationRow row) {
        if (!TYPES.contains(NotificationType.valueOf(row.type())) || row.commentId() == null
                || row.reviewId() != null || row.roundNo() != null) {
            throw new IllegalStateException("Notification facts do not match comment target");
        }
        return visible(row) ? Optional.of(NotificationTarget.comment(row.documentId(), row.versionId(), row.commentId()))
                : Optional.empty();
    }

    static boolean visible(NotificationRow row) {
        return row.live() && row.cleared() && row.discussable() && row.replyReadable()
                && EVIDENCE.contains(row.publicationEvidence());
    }
}
