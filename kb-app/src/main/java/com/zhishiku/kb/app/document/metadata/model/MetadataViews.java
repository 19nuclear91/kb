package com.zhishiku.kb.app.document.metadata.model;

import java.time.OffsetDateTime;
import java.util.List;

public final class MetadataViews {
    private MetadataViews() {
    }

    public record PathNode(long id, String name) {
    }

    public record Category(long id, String name, List<PathNode> path) {
        public Category {
            path = List.copyOf(path);
        }
    }

    public record CategoryItem(long id, Long parentId, String name, int sortOrder, List<PathNode> path) {
        public CategoryItem {
            path = List.copyOf(path);
        }
    }

    public record Tag(long id, String name, boolean enabled) {
    }

    public record ManagedTag(long id, String name, boolean enabled, long version,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record Metadata(long documentId, long spaceId, String title, String summary,
                           Category category, List<Tag> tags, long metadataVersion,
                           OffsetDateTime updatedAt) {
        public Metadata {
            tags = List.copyOf(tags);
        }
    }

    public record Receipt(long documentId, long metadataVersion, OffsetDateTime updatedAt,
                          List<String> changedFields, boolean replayed) {
        public Receipt {
            changedFields = List.copyOf(changedFields);
        }
    }

    public record CategoryReceipt(long categoryId, long treeVersion, boolean replayed) {
    }

    public record TagReceipt(long tagId, long version, boolean replayed) {
    }

    public record CategoryPage(List<CategoryItem> items, boolean hasMore, String nextCursor, long treeVersion) {
        public CategoryPage {
            items = List.copyOf(items);
        }
    }

    public record Page<T>(List<T> items, boolean hasMore, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
