package com.zhishiku.kb.app.document.metadata.model;

import java.time.OffsetDateTime;
import java.util.List;

public final class MetadataInputs {
    private MetadataInputs() {
    }

    public record Update(String title, String summary, Long categoryId, List<Long> tagIds,
                         long expectedMetadataVersion) {
        public Update {
            tagIds = List.copyOf(tagIds);
        }
    }

    public record CategoryWrite(String name, String normalizedName, Long parentId, int sortOrder,
                                long expectedTreeVersion) {
    }

    public record TagWrite(String name, String normalizedName, boolean enabled, Long expectedVersion) {
    }

    public enum FileType {
        PDF, TXT, MARKDOWN, WORD, EXCEL, POWERPOINT, OTHER, UNPUBLISHED
    }

    public record Query(String titleQuery, Long spaceId, Long categoryId, boolean uncategorized,
                        List<Long> tagIds, FileType fileType, OffsetDateTime updatedFrom,
                        OffsetDateTime updatedTo, int page, int size) {
        public Query {
            tagIds = List.copyOf(tagIds);
        }

        public long offset() {
            return ((long) page - 1) * size;
        }
    }

    public record CategoryQuery(Long spaceId, Long parentId, int limit, String cursor) {
    }

    public record TagQuery(String nameQuery, Boolean enabled, int limit, String cursor) {
    }
}
