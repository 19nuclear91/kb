package com.zhishiku.kb.app.document.model;

import com.zhishiku.kb.common.review.PublicationEvidence;

/** 当前授权的最小固定版本投影；标题临时读取，不复制正文、文件或审核私有意见。 */
public record PublishedDocumentReference(long documentId, long versionId, String title,
                                         PublicationEvidence publicationEvidence) {
    public boolean published() {
        return publicationEvidence != null && publicationEvidence.isPublished();
    }
}
