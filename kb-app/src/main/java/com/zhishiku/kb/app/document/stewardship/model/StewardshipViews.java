package com.zhishiku.kb.app.document.stewardship.model;

import com.zhishiku.kb.app.document.applicability.model.ApplicabilityViews;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class StewardshipViews {
    private StewardshipViews() {
    }

    public record Candidates(List<StewardCandidate> items, String nextCursor, LocalDate asOfDate) {
    }

    public record Detail(long documentId, long spaceId, StewardshipState state, StewardCandidate responsible,
                         Integer cycleMonths, LocalDate firstAssignedOn, LocalDate lastConfirmedOn, LocalDate dueDate,
                         long stewardshipVersion, LocalDate asOfDate) {
    }

    public record Receipt(long documentId, long stewardshipVersion, LocalDate dueDate, LocalDate lastConfirmedOn,
                          OffsetDateTime updatedAt, LocalDate asOfDate, boolean replayed) {
        public Receipt replay() {
            return new Receipt(documentId, stewardshipVersion, dueDate, lastConfirmedOn, updatedAt, asOfDate, true);
        }
    }

    public record MineItem(long documentId, String title, StewardshipState state, LocalDate dueDate,
                           LocalDate lastConfirmedOn, int cycleMonths, long stewardshipVersion,
                           List<String> attentionReasons, ApplicabilityViews.Badge currentVersion) {
    }

    public record Mine(List<MineItem> items, String nextCursor, LocalDate asOfDate) {
    }

    public record Count(String count, LocalDate asOfDate) {
    }

    public record SpaceItem(long documentId, StewardshipState state, LocalDate dueDate, StewardCandidate responsible) {
    }

    public record Space(List<SpaceItem> items, String nextCursor, LocalDate asOfDate) {
    }
}
