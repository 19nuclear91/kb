package com.zhishiku.kb.app.document.applicability.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record ApplicabilityRow(long documentId, long versionId, LocalDate effectiveFrom, UntilKind untilKind,
                               LocalDate effectiveUntil, long applicabilityVersion, boolean corrected,
                               OffsetDateTime updatedAt, boolean current) {
    public ApplicabilityDeclaration declaration() {
        return new ApplicabilityDeclaration(effectiveFrom, untilKind, effectiveUntil);
    }
}
