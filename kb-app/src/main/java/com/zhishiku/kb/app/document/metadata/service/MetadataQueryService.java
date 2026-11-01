package com.zhishiku.kb.app.document.metadata.service;

import com.zhishiku.kb.app.document.metadata.mapper.MetadataQueryMapper;
import com.zhishiku.kb.app.document.metadata.mapper.TagMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.MetadataSummary;
import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.metadata.support.MetadataInput;
import com.zhishiku.kb.app.document.metadata.model.DocumentFileTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;

@Service
public class MetadataQueryService {
    private final MetadataQueryMapper mapper;
    private final MetadataAccess access;
    private final CategoryService categories;
    private final TagMapper tags;

    public MetadataQueryService(MetadataQueryMapper mapper, MetadataAccess access, CategoryService categories, TagMapper tags) {
        this.mapper = mapper;
        this.access = access;
        this.categories = categories;
        this.tags = tags;
    }

    public record Page(int page, int size, long total, List<MetadataSummary> items) {
        public Page {
            items = List.copyOf(items);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Page query(MetadataInputs.Query query) {
        mapper.budget();
        var scope = access.view();
        String pattern = MetadataInput.literalPattern(query.titleQuery());
        var principals = Arrays.asList(scope.principalKeys());
        long count = mapper.count(query, pattern, DocumentFileTypes.RULES, scope.visibleSpaceIdList(), principals, scope.clearanceLevel());
        List<MetadataSummary> items = count == 0 ? List.of() : mapper.list(query, pattern, DocumentFileTypes.RULES,
                scope.visibleSpaceIdList(), principals, scope.clearanceLevel());
        Map<Long, List<MetadataViews.Tag>> associated = new HashMap<>();
        if (!items.isEmpty()) {
            for (var tag : tags.forDocuments(items.stream().map(MetadataSummary::getId).toList())) {
                associated.computeIfAbsent(tag.documentId(), ignored -> new ArrayList<>())
                        .add(new MetadataViews.Tag(tag.id(), tag.name(), tag.enabled()));
            }
        }
        Map<Long, MetadataViews.Category> paths = new HashMap<>();
        for (var item : items) {
            if (item.getCategoryId() != null) {
                item.setCategory(paths.computeIfAbsent(item.getCategoryId(), id -> {
                    var path = categories.path(item.getSpaceId(), id);
                    return new MetadataViews.Category(id, path.getLast().name(), path);
                }));
            }
            item.setTags(List.copyOf(associated.getOrDefault(item.getId(), List.of())));
        }
        return new Page(query.page(), query.size(), count, items);
    }
}
