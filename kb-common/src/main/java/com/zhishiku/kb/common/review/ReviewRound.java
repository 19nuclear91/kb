package com.zhishiku.kb.common.review;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一轮审核的稳定身份及送审事实；旧轮次不被新送审覆盖。
 *
 * @param id 审核标识
 * @param documentId 文档标识
 * @param versionId 精确版本标识
 * @param roundNo 单版本递增轮次
 * @param submittedBy 本轮送审者
 * @param submittedSpaceId 本轮实际空间
 * @param submittedAt 服务端送审时间
 * @param state 本轮状态
 */
public record ReviewRound(UUID id, long documentId, long versionId, int roundNo,
                          long submittedBy, long submittedSpaceId, Instant submittedAt, ReviewState state) {
    public ReviewRound {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(state, "state");
        if (documentId <= 0 || versionId <= 0 || roundNo <= 0 || submittedBy <= 0 || submittedSpaceId <= 0) {
            throw new IllegalArgumentException("Invalid review identity");
        }
    }

    /** 调用方须持有版本锁；数据库的部分唯一索引另行守护并发。 */
    public static ReviewRound start(long documentId, long versionDocumentId, long versionId,
                                    ReviewRound previous, long submittedBy, long spaceId, Instant now) {
        if (documentId != versionDocumentId || (previous != null
                && (previous.documentId != documentId || previous.versionId != versionId))) {
            throw new IllegalArgumentException("Review/version ownership mismatch");
        }
        if (previous != null && previous.state == ReviewState.IN_REVIEW) {
            throw new IllegalStateException("STATE_CONFLICT");
        }
        int round = previous == null ? 1 : Math.incrementExact(previous.roundNo);
        return new ReviewRound(UUID.randomUUID(), documentId, versionId, round,
                submittedBy, spaceId, now, ReviewState.IN_REVIEW);
    }
}
