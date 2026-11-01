package com.zhishiku.kb.app.document.metadata.model;

import java.time.OffsetDateTime;

public record MetadataCommandRow(String operationScope, String requestId, long actorUserId,
                                 Long targetId, Long spaceId, Long documentId, Long expectedVersion,
                                 int digestVersion, String inputDigest, String receipt,
                                 OffsetDateTime createdAt) {
}
