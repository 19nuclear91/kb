package com.zhishiku.kb.app.document.changenote.model;

import java.time.OffsetDateTime;
import java.util.List;

public final class ChangeNoteViews {
    private ChangeNoteViews() {
    }

    public record Detail(long documentId, long versionId, boolean hasNote, String text, Long baseVersionId,
                         Integer baseVersionNo, boolean corrected, boolean createdAfterVisible, long noteVersion,
                         Long writeBaseVersionId, Integer writeBaseVersionNo, boolean editable,
                         boolean reviewFrozen, boolean visibleToReaders) {
    }

    public record Receipt(long documentId, long versionId, long noteVersion, boolean corrected,
                          boolean createdAfterVisible, OffsetDateTime updatedAt, boolean replayed) {
        public Receipt replay() {
            return new Receipt(documentId, versionId, noteVersion, corrected, createdAfterVisible, updatedAt, true);
        }
    }

    public record History(List<ChangeNoteHistory> items, String nextCursor) {
    }
}
