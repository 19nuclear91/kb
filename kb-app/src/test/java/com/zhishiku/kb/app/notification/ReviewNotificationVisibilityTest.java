package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.notification.service.NotificationTargetRegistry;
import com.zhishiku.kb.app.notification.service.ReviewNotificationTargets;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审核送审／结果目标的资格矩阵。事实列由同源授权片段在 SQL 中计算，本测试执行 Java 复核规则，
 * 不连接数据库，也不证明真实 SQL 的执行计划或权限数据。
 */
class ReviewNotificationVisibilityTest {
    private static final long RECIPIENT = 7, SUBMITTER = 8, UPLOADER = 9;
    private static final UUID REVIEW = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final ReviewNotificationTargets targets = new ReviewNotificationTargets();

    @Test
    void pendingReviewIsVisibleOnlyWhileTheSameRoundIsActionableByAnEligibleNonSelfReviewer() {
        var base = pending();
        assertThat(targets.resolve(base)).contains(NotificationTarget.review(10, 20, REVIEW));
        Map<String, UnaryOperator<Facts>> hidden = Map.ofEntries(
                Map.entry("当前不可处理：撤销 view/review 或主体停用", facts -> facts.actionable(false)),
                Map.entry("升密", facts -> facts.cleared(false)),
                Map.entry("文档或空间归档／删除", facts -> facts.live(false)),
                Map.entry("轮次已批准", facts -> facts.roundState("APPROVED")),
                Map.entry("轮次已驳回", facts -> facts.roundState("REJECTED")),
                Map.entry("轮次已撤回", facts -> facts.roundState("WITHDRAWN")),
                Map.entry("轮次因刷新／搬迁取消", facts -> facts.roundState("CANCELLED")),
                Map.entry("文档已搬迁到其他空间", facts -> facts.roundInSpace(false)),
                Map.entry("版本已发布", facts -> facts.evidence("PUBLISHED")),
                Map.entry("收件人是本轮送审者", facts -> facts.submittedBy(RECIPIENT)),
                Map.entry("收件人是上传者", facts -> facts.uploader(RECIPIENT)),
                Map.entry("旧版本上传者未知", facts -> facts.uploader(null)),
                Map.entry("免审策略", facts -> facts.policy("AUTO")));
        hidden.forEach((name, change) -> assertThat(targets.resolve(change.apply(new Facts(base)).row())).as(name).isEmpty());
        // 权限恢复后，仍保存的关系重新可见。
        var revoked = new Facts(base).actionable(false).row();
        assertThat(targets.resolve(revoked)).isEmpty();
        assertThat(targets.resolve(new Facts(revoked).actionable(true).row())).isPresent();
    }

    @Test
    void resultIsVisibleOnlyToThisRoundSubmitterWithCurrentInteractiveRead() {
        for (String type : List.of("REVIEW_APPROVED", "REVIEW_REJECTED")) {
            var base = result(type);
            assertThat(targets.resolve(base)).as(type).contains(NotificationTarget.review(10, 20, REVIEW));
            Map<String, UnaryOperator<Facts>> hidden = Map.of(
                    "edit-only 送审者无 view", facts -> facts.viewable(false),
                    "升密", facts -> facts.cleared(false),
                    "归档或删除", facts -> facts.live(false),
                    "所有者不是送审者", facts -> facts.submittedBy(UPLOADER),
                    "轮次结果与类型不一致", facts -> facts.roundState(type.equals("REVIEW_APPROVED") ? "REJECTED" : "APPROVED"),
                    "轮次已不存在", facts -> facts.roundState(null).submittedBy(null));
            hidden.forEach((name, change) -> assertThat(targets.resolve(change.apply(new Facts(base)).row())).as(type + name).isEmpty());
            // 结果提醒不要求审核资格；搬迁后只要当前可读仍可见。
            assertThat(targets.resolve(new Facts(base).actionable(false).roundInSpace(false).row())).isPresent();
            var revoked = new Facts(base).viewable(false).row();
            assertThat(targets.resolve(new Facts(revoked).viewable(true).row())).isPresent();
        }
    }

    @Test
    void inconsistentFactsFailClosedInsteadOfGuessingATarget() {
        assertThatThrownBy(() -> targets.resolve(new Facts(pending()).review(null).row())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> targets.resolve(new Facts(pending()).type("COMMENT_REPLIED").row()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registryExposesOnlyInstalledTypesAndRejectsOverlap() {
        var registry = new NotificationTargetRegistry(List.of(new ReviewNotificationTargets()));
        assertThat(registry.readableTypes()).containsExactly("REVIEW_SUBMITTED", "REVIEW_APPROVED", "REVIEW_REJECTED");
        assertThat(registry.supports(NotificationType.COMMENT_REPLIED)).isFalse();
        assertThat(new NotificationTargetRegistry(List.of()).readableTypes()).isEmpty();
        assertThatThrownBy(() -> new NotificationTargetRegistry(List.of(new ReviewNotificationTargets(), new ReviewNotificationTargets())))
                .isInstanceOf(IllegalStateException.class);
    }

    private static NotificationRow pending() {
        return new NotificationRow(1, UUID.randomUUID(), RECIPIENT, "REVIEW_SUBMITTED", Instant.EPOCH, null, 10, 20, REVIEW, 1,
                true, true, true, true, "IN_REVIEW", SUBMITTER, true, UPLOADER, "REVIEW_REQUIRED", "NONE", null, false, false);
    }

    private static NotificationRow result(String type) {
        return new NotificationRow(1, UUID.randomUUID(), RECIPIENT, type, Instant.EPOCH, null, 10, 20, REVIEW, 1,
                true, true, true, false, type.substring("REVIEW_".length()), RECIPIENT, true, UPLOADER, "REVIEW_REQUIRED",
                type.equals("REVIEW_APPROVED") ? "PUBLISHED" : "NONE", null, false, false);
    }

    /** 可变的事实构造器，只用于组合矩阵。 */
    private static final class Facts {
        private NotificationRow row;

        Facts(NotificationRow row) {
            this.row = row;
        }

        NotificationRow row() {
            return row;
        }

        Facts live(boolean value) {
            return with(value, row.cleared(), row.viewable(), row.actionable(), row.roundState(), row.roundSubmittedBy(),
                    row.roundInSpace(), row.uploaderUserId(), row.publicationPolicy(), row.publicationEvidence());
        }

        Facts cleared(boolean value) {
            return with(row.live(), value, row.viewable(), row.actionable(), row.roundState(), row.roundSubmittedBy(),
                    row.roundInSpace(), row.uploaderUserId(), row.publicationPolicy(), row.publicationEvidence());
        }

        Facts viewable(boolean value) {
            return with(row.live(), row.cleared(), value, row.actionable(), row.roundState(), row.roundSubmittedBy(),
                    row.roundInSpace(), row.uploaderUserId(), row.publicationPolicy(), row.publicationEvidence());
        }

        Facts actionable(boolean value) {
            return with(row.live(), row.cleared(), row.viewable(), value, row.roundState(), row.roundSubmittedBy(),
                    row.roundInSpace(), row.uploaderUserId(), row.publicationPolicy(), row.publicationEvidence());
        }

        Facts roundState(String value) {
            return with(row.live(), row.cleared(), row.viewable(), row.actionable(), value, row.roundSubmittedBy(),
                    row.roundInSpace(), row.uploaderUserId(), row.publicationPolicy(), row.publicationEvidence());
        }

        Facts submittedBy(Long value) {
            return with(row.live(), row.cleared(), row.viewable(), row.actionable(), row.roundState(), value,
                    row.roundInSpace(), row.uploaderUserId(), row.publicationPolicy(), row.publicationEvidence());
        }

        Facts roundInSpace(boolean value) {
            return with(row.live(), row.cleared(), row.viewable(), row.actionable(), row.roundState(), row.roundSubmittedBy(),
                    value, row.uploaderUserId(), row.publicationPolicy(), row.publicationEvidence());
        }

        Facts uploader(Long value) {
            return with(row.live(), row.cleared(), row.viewable(), row.actionable(), row.roundState(), row.roundSubmittedBy(),
                    row.roundInSpace(), value, row.publicationPolicy(), row.publicationEvidence());
        }

        Facts policy(String value) {
            return with(row.live(), row.cleared(), row.viewable(), row.actionable(), row.roundState(), row.roundSubmittedBy(),
                    row.roundInSpace(), row.uploaderUserId(), value, row.publicationEvidence());
        }

        Facts evidence(String value) {
            return with(row.live(), row.cleared(), row.viewable(), row.actionable(), row.roundState(), row.roundSubmittedBy(),
                    row.roundInSpace(), row.uploaderUserId(), row.publicationPolicy(), value);
        }

        Facts review(UUID value) {
            row = new NotificationRow(row.id(), row.notificationId(), row.recipientUserId(), row.type(), row.createdAt(),
                    row.readAt(), row.documentId(), row.versionId(), value, row.roundNo(), row.live(), row.cleared(),
                    row.viewable(), row.actionable(), row.roundState(), row.roundSubmittedBy(), row.roundInSpace(),
                    row.uploaderUserId(), row.publicationPolicy(), row.publicationEvidence(), row.commentId(),
                    row.discussable(), row.replyReadable());
            return this;
        }

        Facts type(String value) {
            row = new NotificationRow(row.id(), row.notificationId(), row.recipientUserId(), value, row.createdAt(),
                    row.readAt(), row.documentId(), row.versionId(), row.reviewId(), row.roundNo(), row.live(), row.cleared(),
                    row.viewable(), row.actionable(), row.roundState(), row.roundSubmittedBy(), row.roundInSpace(),
                    row.uploaderUserId(), row.publicationPolicy(), row.publicationEvidence(), row.commentId(),
                    row.discussable(), row.replyReadable());
            return this;
        }

        private Facts with(boolean live, boolean cleared, boolean viewable, boolean actionable, String state, Long submittedBy,
                           boolean inSpace, Long uploader, String policy, String evidence) {
            row = new NotificationRow(row.id(), row.notificationId(), row.recipientUserId(), row.type(), row.createdAt(),
                    row.readAt(), row.documentId(), row.versionId(), row.reviewId(), row.roundNo(), live, cleared, viewable,
                    actionable, state, submittedBy, inSpace, uploader, policy, evidence, row.commentId(),
                    row.discussable(), row.replyReadable());
            return this;
        }
    }
}
