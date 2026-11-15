package com.zhishiku.kb.app.document.applicability.model;

import java.time.LocalDate;
import java.util.Objects;

public enum ApplicabilityState {
    UNDECLARED,
    NOT_YET_EFFECTIVE,
    EXPIRED,
    EXPIRING_SOON,
    EFFECTIVE;

    public static ApplicabilityState of(ApplicabilityDeclaration declaration, LocalDate today) {
        Objects.requireNonNull(declaration);
        Objects.requireNonNull(today);
        if (declaration.effectiveFrom() == null && declaration.untilKind() == UntilKind.UNDECLARED) {
            return UNDECLARED;
        }
        if (declaration.effectiveFrom() != null && declaration.effectiveFrom().isAfter(today)) {
            return NOT_YET_EFFECTIVE;
        }
        if (declaration.effectiveUntil() != null) {
            if (declaration.effectiveUntil().isBefore(today)) {
                return EXPIRED;
            }
            if (!declaration.effectiveUntil().isAfter(today.plusDays(30))) {
                return EXPIRING_SOON;
            }
        }
        return EFFECTIVE;
    }
}
