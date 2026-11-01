package com.zhishiku.kb.app.document.metadata.model;

import java.time.OffsetDateTime;

public record CategoryRow(long id, long spaceId, Long parentId, String name,
                          String normalizedName, int sortOrder,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
