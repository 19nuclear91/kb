package com.zhishiku.kb.app.document.model;

import com.zhishiku.kb.common.review.ReviewRound;
import com.zhishiku.kb.common.review.ReviewState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/** 私有轮次持久行；不得直接作为普通文档摘要或回执返回。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentReviewRow {
    private UUID id;
    private long docId;
    private long versionId;
    private int roundNo;
    private long submittedBy;
    private long submittedSpaceId;
    private ReviewState state;
    private long stateVersion;
    private Instant submittedAt;
    private Long decisionBy;
    private Instant decidedAt;
    @ToString.Exclude
    private String reason;

    public ReviewRound round() {
        return new ReviewRound(id, docId, versionId, roundNo, submittedBy, submittedSpaceId, submittedAt, state);
    }

    public static DocumentReviewRow from(ReviewRound round) {
        return DocumentReviewRow.builder().id(round.id()).docId(round.documentId()).versionId(round.versionId())
                .roundNo(round.roundNo()).submittedBy(round.submittedBy()).submittedSpaceId(round.submittedSpaceId())
                .state(round.state()).submittedAt(round.submittedAt()).build();
    }
}
