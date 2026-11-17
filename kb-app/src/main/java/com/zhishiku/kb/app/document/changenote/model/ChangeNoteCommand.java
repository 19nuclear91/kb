package com.zhishiku.kb.app.document.changenote.model;

public record ChangeNoteCommand(String operationScope, String requestId, long actorUserId, long targetId,
                                long documentId, long expectedVersion, int digestVersion, String inputDigest, String receipt) {
}
