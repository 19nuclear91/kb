package com.zhishiku.kb.app.document.model;

import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.PublicationProtocol;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 审核事务所需的最小版本事实，不包含标题、文件名、存储键或正文。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewVersionState {
    private long documentId;
    private long versionId;
    private long spaceId;
    private String documentStatus;
    private boolean spaceArchived;
    private int versionNo;
    private Integer currentVersionNo;
    private Long currentVersionId;
    private Long uploaderUserId;
    private PublicationPolicy publicationPolicy;
    private PublicationProtocol publicationProtocol;
    private PublicationEvidence publicationEvidence;
    private long stateVersion;
    private String indexStatus;
    private String indexedProfileId;
    private String indexedFingerprint;
    private Long indexedEpoch;
    private Integer indexedChunkCount;
    private Instant publishedAt;

    public boolean live() {
        return !spaceArchived && !"deleted".equals(documentStatus) && !"archived".equals(documentStatus);
    }
}
