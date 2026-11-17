package com.zhishiku.kb.app.document.changenote.model;

public record ChangeNoteTarget(long documentId, long versionId, long spaceId, String documentStatus,
                               boolean spaceArchived, String publicationProtocol, String publicationEvidence,
                               boolean reviewInProgress) {
    public boolean visibleToReaders() {
        return "LEGACY_COMPAT".equals(publicationProtocol) || "PUBLISHED".equals(publicationEvidence)
                || "OBSERVED_CURRENT".equals(publicationEvidence);
    }
}
