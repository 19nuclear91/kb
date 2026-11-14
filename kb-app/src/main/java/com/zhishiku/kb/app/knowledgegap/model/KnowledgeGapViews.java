package com.zhishiku.kb.app.knowledgegap.model;

import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;

import java.time.Instant;
import java.util.UUID;

/** 安全响应投影；持久化行不直接作为详情或历史响应返回。 */
public final class KnowledgeGapViews {
    private KnowledgeGapViews() {
    }

    public enum ReferenceState {
        NONE, AVAILABLE, UNAVAILABLE
    }

    public record Participant(long userId, String label) {
    }

    public record Entry(UUID entryId, long position, String kind, Participant author, String text,
                        ReferenceState referenceState, KnowledgeGapDocumentOption reference, Instant createdAt) {
        @Override
        public String toString() {
            return "EntryView[redacted]";
        }
    }

    public record Item(long gapId, KnowledgeGapCategory category, KnowledgeGapSource source, KnowledgeGapState state,
                       long version, Participant reporter, Participant assignee, boolean needsReassignment,
                       Instant createdAt, Instant updatedAt) {
    }

    public record Detail(long gapId, KnowledgeGapCategory category, KnowledgeGapSource source, String description,
                         String consentVersion, KnowledgeGapState state, long version, Participant reporter,
                         Participant assignee, boolean needsReassignment, Instant createdAt, Instant updatedAt,
                         KnowledgeGapAccess.Capabilities capabilities, Entry currentSolution) {
        @Override
        public String toString() {
            return "Detail[redacted]";
        }
    }

    public record HistoryItem(UUID eventId, String kind, long resultVersion, Participant actor,
                              Participant assignee, Instant occurredAt, UUID solutionId, Entry entry) {
        @Override
        public String toString() {
            return "HistoryItem[redacted]";
        }
    }
}
