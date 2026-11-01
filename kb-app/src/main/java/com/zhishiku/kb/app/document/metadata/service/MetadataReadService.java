package com.zhishiku.kb.app.document.metadata.service;

import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.mapper.TagMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.NOT_FOUND;

@Service
public class MetadataReadService {
    private final MetadataMapper mapper;
    private final TagMapper tags;
    private final CategoryService categories;
    private final MetadataAccess access;

    public MetadataReadService(MetadataMapper mapper, TagMapper tags, CategoryService categories, MetadataAccess access) {
        this.mapper = mapper;
        this.tags = tags;
        this.categories = categories;
        this.access = access;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public MetadataViews.Metadata get(long id) {
        access.document(id, false);
        var row = mapper.document(id);
        if (row == null) {
            throw new MetadataException(NOT_FOUND);
        }
        return project(row);
    }

    public MetadataViews.Metadata project(DocumentEntity row) {
        MetadataViews.Category category = null;
        if (row.getCategoryId() != null) {
            var path = categories.path(row.getSpaceId(), row.getCategoryId());
            category = new MetadataViews.Category(row.getCategoryId(), path.getLast().name(), path);
        }
        var values = tags.forDocument(row.getId()).stream()
                .map(tag -> new MetadataViews.Tag(tag.id(), tag.name(), tag.enabled())).toList();
        return new MetadataViews.Metadata(row.getId(), row.getSpaceId(), row.getTitle(), row.getSummary(), category,
                values, row.getMetadataVersion(), row.getUpdatedAt());
    }
}
