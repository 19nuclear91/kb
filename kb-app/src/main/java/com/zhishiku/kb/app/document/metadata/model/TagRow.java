package com.zhishiku.kb.app.document.metadata.model;

import java.time.OffsetDateTime;

public record TagRow(long id, String name, String normalizedName, boolean enabled,
                     long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
