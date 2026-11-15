package com.zhishiku.kb.app.document.applicability.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ApplicabilityViews {
    private ApplicabilityViews() {
    }

    public record Detail(long documentId, long versionId, LocalDate effectiveFrom, UntilKind untilKind,
                         LocalDate effectiveUntil, ApplicabilityState state, boolean corrected, long applicabilityVersion,
                         LocalDate asOfDate, boolean canEdit, boolean reviewFrozen, boolean visibleToReaders) {
    }

    public record Badge(long documentId, long versionId, ApplicabilityState state, LocalDate effectiveFrom,
                        UntilKind untilKind, LocalDate effectiveUntil, boolean corrected, boolean current) {
        public static Badge of(ApplicabilityRow row, LocalDate today) {
            return new Badge(row.documentId(), row.versionId(), ApplicabilityState.of(row.declaration(), today),
                    row.effectiveFrom(), row.untilKind(), row.effectiveUntil(), row.corrected(), row.current());
        }
    }

    public record Batch(List<Badge> items, LocalDate asOfDate) {
    }

    public record History(List<ApplicabilityChange> items, String nextCursor, LocalDate asOfDate) {
    }

    public record Receipt(long documentId, long versionId, long applicabilityVersion, boolean corrected,
                          OffsetDateTime updatedAt, LocalDate asOfDate, boolean replayed) {
        public Receipt replay() {
            return new Receipt(documentId, versionId, applicabilityVersion, corrected, updatedAt, asOfDate, true);
        }
    }
}
