package com.zhishiku.kb.app.document.applicability.model;

public record ValidityCommandRow(String operationScope, String requestId, long actorUserId, long targetId,
                                 long documentId, long expectedVersion, int digestVersion, String inputDigest,
                                 String receipt) {
}
