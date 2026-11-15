package com.zhishiku.kb.app.document.applicability.model;

import java.time.LocalDate;

public record ApplicabilityUpdate(LocalDate effectiveFrom, UntilKind untilKind, LocalDate effectiveUntil,
                                  String reason, long expectedApplicabilityVersion) {
    public ApplicabilityDeclaration declaration() {
        return new ApplicabilityDeclaration(effectiveFrom, untilKind, effectiveUntil);
    }
}
