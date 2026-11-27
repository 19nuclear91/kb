package com.zhishiku.kb.app.domain.dto.subscription;

import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 私有授权查询结果，标题仅出现在本人当前可读投影。 */
public final class KnowledgeRows {
    private KnowledgeRows() {
    }

    public record Update(
            UUID updateId,
            long position,
            long documentId,
            long versionId,
            int versionNo,
            String title,
            Instant publishedAt,
            LocalDate weekStart,
            SubscriptionMode mode
    ) {
    }

    public record Digest(
            UUID digestId,
            LocalDate weekStart,
            Instant generatedAt
    ) {
    }

    public record Group(
            long documentId,
            String title,
            long publicationCount,
            long position,
            Instant latestPublishedAt
    ) {
    }

    public record Base(
            long versionId,
            int versionNo
    ) {
    }

    public record Notification(
            UUID deliveryId,
            Long userId,
            String kind,
            String status,
            UUID updateId,
            LocalDate weekStart,
            Boolean visible
    ) {
    }
}
