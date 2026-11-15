package com.zhishiku.kb.app.document.applicability.model;

import java.time.LocalDate;

/** 原始声明，不把未声明的截止日解释为长期有效。 */
public record ApplicabilityDeclaration(LocalDate effectiveFrom, UntilKind untilKind, LocalDate effectiveUntil) {
    public ApplicabilityDeclaration {
        if (untilKind == null || (untilKind == UntilKind.DATE) != (effectiveUntil != null)
                || !inRange(effectiveFrom) || !inRange(effectiveUntil)
                || (effectiveFrom != null && effectiveUntil != null && effectiveUntil.isBefore(effectiveFrom))) {
            throw new IllegalArgumentException("适用期声明无效");
        }
    }

    public static ApplicabilityDeclaration undeclared() {
        return new ApplicabilityDeclaration(null, UntilKind.UNDECLARED, null);
    }

    private static boolean inRange(LocalDate date) {
        return date == null || (date.getYear() >= 1900 && date.getYear() <= 2999);
    }
}
