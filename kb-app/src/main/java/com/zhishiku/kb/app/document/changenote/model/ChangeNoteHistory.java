package com.zhishiku.kb.app.document.changenote.model;

import java.time.OffsetDateTime;

public record ChangeNoteHistory(long id, long documentId, long versionId, OffsetDateTime changedAt,
                                String textBefore, String textAfter, Long baseBefore, Long baseAfter,
                                boolean visibleToReaders, String reason) {
}
