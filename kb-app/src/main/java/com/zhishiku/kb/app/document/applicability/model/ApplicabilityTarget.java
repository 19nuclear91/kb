package com.zhishiku.kb.app.document.applicability.model;

import java.util.Set;

public record ApplicabilityTarget(long documentId, long versionId, long spaceId, String documentStatus,
                                  boolean spaceArchived, String publicationProtocol, String publicationEvidence,
                                  boolean reviewInProgress) {
    public boolean visibleToReaders() {
        return "LEGACY_COMPAT".equals(publicationProtocol)
                || Set.of("PUBLISHED", "OBSERVED_CURRENT").contains(publicationEvidence);
    }
}
