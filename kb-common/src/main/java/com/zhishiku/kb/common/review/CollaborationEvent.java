package com.zhishiku.kb.common.review;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 追加式协作事实协议 v1，结构上不接受正文、文件名、意见或凭据。
 *
 * @param eventId 稳定事实身份
 * @param kind 固定事件种类
 * @param businessKey 唯一业务键
 * @param documentId 文档标识
 * @param versionId 版本标识
 * @param reviewId 审核标识；自动发布与评论事实为空
 * @param roundNo 审核轮次；自动发布与评论事实为空
 * @param actorUserId 服务端操作者；系统发布允许为空，评论事实必填
 * @param occurredAt 服务端事实时间
 * @param submittedAt 本轮送审时间
 * @param decidedAt 本轮决定时间
 * @param commentId 评论事实的评论身份；其他事实为空
 * @param rootCommentId 回复所属根评论；根评论与其他事实为空，删除事实保留原结构
 */
public record CollaborationEvent(UUID eventId, CollaborationKind kind, String businessKey,
                                  long documentId, long versionId, UUID reviewId, Integer roundNo,
                                  Long actorUserId, Instant occurredAt, Instant submittedAt, Instant decidedAt,
                                  UUID commentId, UUID rootCommentId) {
    public static final int PROTOCOL_VERSION = 1;

    public CollaborationEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (documentId <= 0 || versionId <= 0 || (actorUserId != null && actorUserId <= 0)) {
            throw new IllegalArgumentException("Invalid event target");
        }
        if (kind.comment()) {
            if (reviewId != null || roundNo != null || submittedAt != null || decidedAt != null
                    || actorUserId == null || commentId == null) {
                throw new IllegalArgumentException("Invalid comment event");
            }
            if ((kind == CollaborationKind.COMMENT_CREATED && rootCommentId != null)
                    || (kind == CollaborationKind.COMMENT_REPLIED && rootCommentId == null)
                    || commentId.equals(rootCommentId)) {
                throw new IllegalArgumentException("Invalid comment structure");
            }
        } else {
            if (commentId != null || rootCommentId != null) {
                throw new IllegalArgumentException("Unexpected comment target");
            }
            if ((reviewId == null) != (roundNo == null)
                    || (reviewId == null && kind != CollaborationKind.VERSION_PUBLISHED)
                    || (reviewId != null && (roundNo <= 0 || submittedAt == null))) {
                throw new IllegalArgumentException("Invalid event round");
            }
            if (kind != CollaborationKind.REVIEW_SUBMITTED && kind != CollaborationKind.VERSION_PUBLISHED
                    && decidedAt == null) {
                throw new IllegalArgumentException("Missing decision time");
            }
        }
        String expected = key(kind, versionId, reviewId, commentId);
        if (!expected.equals(businessKey) || !identity(expected).equals(eventId)) {
            throw new IllegalArgumentException("Invalid event identity");
        }
    }

    public static CollaborationEvent create(CollaborationKind kind, long documentId, long versionId,
                                              ReviewRound round, Long actor, Instant now, Instant decidedAt) {
        if (kind.comment()) {
            throw new IllegalArgumentException("Comment facts require comment identity");
        }
        UUID reviewId = round == null ? null : round.id();
        if (round != null && (round.documentId() != documentId || round.versionId() != versionId)) {
            throw new IllegalArgumentException("Event/round ownership mismatch");
        }
        String key = key(kind, versionId, reviewId, null);
        return new CollaborationEvent(identity(key), kind, key, documentId, versionId, reviewId,
                round == null ? null : round.roundNo(), actor, now, round == null ? null : round.submittedAt(), decidedAt,
                null, null);
    }

    /**
     * 评论事实：业务键按 kind + commentId 去重，同一评论的同类事实至多一条。
     *
     * @param rootCommentId 回复及其删除事实的根评论；根评论为空
     */
    public static CollaborationEvent comment(CollaborationKind kind, long documentId, long versionId,
                                               UUID commentId, UUID rootCommentId, long actor, Instant now) {
        if (!kind.comment() || commentId == null) {
            throw new IllegalArgumentException("Not a comment fact");
        }
        String key = key(kind, versionId, null, commentId);
        return new CollaborationEvent(identity(key), kind, key, documentId, versionId, null, null, actor, now,
                null, null, commentId, rootCommentId);
    }

    private static String key(CollaborationKind kind, long versionId, UUID reviewId, UUID commentId) {
        if (kind.comment()) {
            return kind.name() + ":" + commentId;
        }
        return kind.name() + ":" + (kind == CollaborationKind.VERSION_PUBLISHED ? versionId : reviewId);
    }

    private static UUID identity(String key) {
        return UUID.nameUUIDFromBytes(("collaboration:v1:" + key).getBytes(StandardCharsets.UTF_8));
    }
}
