package com.zhishiku.kb.app.document.changenote.model;

import java.time.OffsetDateTime;

public record ChangeNoteRow(long documentId, long versionId, String text, Long baseVersionId,
                            Integer baseVersionNo, long noteVersion, boolean corrected,
                            boolean createdAfterVisible, OffsetDateTime updatedAt) {
    public boolean hasNote() {
        return text != null;
    }
}
