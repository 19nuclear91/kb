package com.zhishiku.kb.worker.parse.model;

import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.PublicationProtocol;
import com.zhishiku.kb.common.review.VersionPublication;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/** Worker 持久层的发布事实，不依赖 app 实体。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerPublicationState {
    private long documentId;
    private long versionId;
    private int versionNo;
    private Integer currentVersionNo;
    private String documentStatus;
    private boolean spaceArchived;
    private PublicationPolicy publicationPolicy;
    private PublicationProtocol publicationProtocol;
    private PublicationEvidence publicationEvidence;
    private Long uploaderUserId;
    private Instant publishedAt;

    public VersionPublication publication() {
        return new VersionPublication(publicationPolicy, publicationProtocol, uploaderUserId, publicationEvidence, publishedAt);
    }

    public boolean canAutoPublish() {
        return !spaceArchived && !"archived".equals(documentStatus) && !"deleted".equals(documentStatus)
                && (currentVersionNo == null || versionNo > currentVersionNo) && publication().permitsPublication("indexed", null);
    }
}
