package com.zhishiku.kb.app.notification.model;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 经校验的站内结构化目标；只描述固定版本或轮次，不含 URL、令牌或标题。
 *
 * @param kind 固定目标种类
 * @param documentId 文档标识
 * @param versionId 固定版本标识，打开时不得替换为最新版本
 * @param reviewId 审核轮次标识；评论目标为空
 * @param commentId 评论对外标识；审核目标为空
 */
@Schema(description = "通知的受控站内目标，打开时仍需源业务重新授权")
@JsonSerialize(using = NotificationTargetJson.Serializer.class)
@JsonDeserialize(using = NotificationTargetJson.Deserializer.class)
public record NotificationTarget(
        @Schema(description = "DOCUMENT_VERSION_REVIEW审核、DOCUMENT_VERSION_COMMENT评论、KNOWLEDGE_GAP问题、KNOWLEDGE_UPDATE个人更新、KNOWLEDGE_DIGEST周报")
        String kind,
        @Schema(description = "文档标识；仅文档目标序列化此字段")
        Long documentId,
        @Schema(description = "固定版本标识；仅文档目标序列化此字段")
        Long versionId,
        @Schema(description = "审核轮次UUID；评论目标为空")
        UUID reviewId,
        @Schema(description = "评论UUID；审核目标为空")
        UUID commentId,
        @Schema(description = "问题标识；仅问题目标序列化此字段")
        Long gapId,
        @Schema(description = "本人固定发布更新UUID；仅更新目标序列化此字段")
        UUID updateId,
        @Schema(description = "本人固定统计周期周报UUID；仅周报目标序列化此字段")
        UUID digestId
) {
    public static final String REVIEW = "DOCUMENT_VERSION_REVIEW";
    public static final String COMMENT = "DOCUMENT_VERSION_COMMENT";
    public static final String KNOWLEDGE_GAP = "KNOWLEDGE_GAP";
    public static final String KNOWLEDGE_UPDATE = "KNOWLEDGE_UPDATE";
    public static final String KNOWLEDGE_DIGEST = "KNOWLEDGE_DIGEST";
    private static final Set<String> KINDS = Set.of(REVIEW, COMMENT, KNOWLEDGE_GAP, KNOWLEDGE_UPDATE, KNOWLEDGE_DIGEST);

    public NotificationTarget {
        boolean gap = KNOWLEDGE_GAP.equals(kind);
        boolean subscription = KNOWLEDGE_UPDATE.equals(kind) || KNOWLEDGE_DIGEST.equals(kind);
        if (kind == null || !KINDS.contains(kind)
                || (gap && (gapId == null || gapId <= 0 || documentId != null || versionId != null || reviewId != null || commentId != null))
                || (!gap && !subscription && (gapId != null || documentId == null || documentId <= 0 || versionId == null || versionId <= 0))
                || (!subscription && (updateId != null || digestId != null))
                || (subscription && (gapId != null || documentId != null || versionId != null || reviewId != null || commentId != null))
                || (KNOWLEDGE_UPDATE.equals(kind) && (updateId == null || digestId != null))
                || (KNOWLEDGE_DIGEST.equals(kind) && (digestId == null || updateId != null))
                || (REVIEW.equals(kind) && (reviewId == null || commentId != null))
                || (COMMENT.equals(kind) && (reviewId != null || commentId == null))) {
            throw new IllegalArgumentException("Invalid notification target");
        }
    }

    /** 既有 Java 调用保持原构造合同；旧目标 JSON 的五个字段完全不变。 */
    public NotificationTarget(String kind, long documentId, long versionId, UUID reviewId, UUID commentId) {
        this(kind, documentId, versionId, reviewId, commentId, null);
    }

    public NotificationTarget(String kind, Long documentId, Long versionId, UUID reviewId, UUID commentId, Long gapId) {
        this(kind, documentId, versionId, reviewId, commentId, gapId, null, null);
    }

    public static NotificationTarget review(long documentId, long versionId, UUID reviewId) {
        return new NotificationTarget(REVIEW, documentId, versionId, Objects.requireNonNull(reviewId), null);
    }

    public static NotificationTarget comment(long documentId, long versionId, UUID commentId) {
        return new NotificationTarget(COMMENT, documentId, versionId, null, Objects.requireNonNull(commentId));
    }

    public static NotificationTarget knowledgeGap(long gapId) {
        return new NotificationTarget(KNOWLEDGE_GAP, null, null, null, null, gapId);
    }

    public static NotificationTarget knowledgeUpdate(UUID updateId) {
        return new NotificationTarget(KNOWLEDGE_UPDATE, null, null, null, null, null, updateId, null);
    }

    public static NotificationTarget knowledgeDigest(UUID digestId) {
        return new NotificationTarget(KNOWLEDGE_DIGEST, null, null, null, null, null, null, digestId);
    }
}
