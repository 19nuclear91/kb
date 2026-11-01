package com.zhishiku.kb.app.document.metadata.service;

import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.model.CategoryRow;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Arrays;

import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.UNAVAILABLE;

@Service
public class CategoryService {
    private final CategoryMapper mapper;
    private final MetadataAccess access;

    public CategoryService(CategoryMapper mapper, MetadataAccess access) {
        this.mapper = mapper;
        this.access = access;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public MetadataViews.CategoryPage managed(long spaceId, MetadataInputs.CategoryQuery query) {
        access.manager(spaceId);
        return page("category-manager", spaceId, spaceId, query);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public MetadataViews.CategoryPage editable(long documentId, MetadataInputs.CategoryQuery query) {
        var current = access.document(documentId, true);
        return page("category-edit", documentId, current.document().getSpaceId(), query);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public MetadataViews.CategoryPage filter(MetadataInputs.CategoryQuery query) {
        var scope = access.view();
        long spaceId = query.spaceId();
        Long storedVersion = mapper.version(spaceId);
        long version = storedVersion == null ? 0 : storedVersion;
        String context = MetadataCursor.context("category-filter", spaceId, query.parentId(), "");
        MetadataCursor cursor = MetadataCursor.decode(query.cursor(), context, spaceId, version);
        var rows = mapper.visibleChildren(spaceId, query.parentId(), cursor == null ? null : cursor.sortOrder(),
                cursor == null ? null : cursor.id(), query.limit() + 1, scope.visibleSpaceIdList(),
                Arrays.asList(scope.principalKeys()), scope.clearanceLevel());
        if (rows.isEmpty()) {
            return new MetadataViews.CategoryPage(List.of(), false, null, 0);
        }
        return project(rows, query.limit(), spaceId, version, context);
    }

    private MetadataViews.CategoryPage page(String purpose, long resourceId, long spaceId, MetadataInputs.CategoryQuery query) {
        Long version = mapper.version(spaceId);
        if (version == null) {
            throw new MetadataException(UNAVAILABLE);
        }
        String context = MetadataCursor.context(purpose, resourceId, query.parentId(), "");
        MetadataCursor cursor = MetadataCursor.decode(query.cursor(), context, spaceId, version);
        List<CategoryRow> rows = mapper.children(spaceId, query.parentId(), cursor == null ? null : cursor.sortOrder(),
                cursor == null ? null : cursor.id(), query.limit() + 1);
        return project(rows, query.limit(), spaceId, version, context);
    }

    private MetadataViews.CategoryPage project(List<CategoryRow> rows, int limit, long spaceId, long version, String context) {
        boolean more = rows.size() > limit;
        List<CategoryRow> slice = rows.subList(0, Math.min(limit, rows.size()));
        List<MetadataViews.CategoryItem> items = slice.stream().map(row -> new MetadataViews.CategoryItem(row.id(),
                row.parentId(), row.name(), row.sortOrder(), path(spaceId, row.id()))).toList();
        String next = null;
        if (more) {
            CategoryRow last = slice.getLast();
            next = new MetadataCursor(context, spaceId, version, last.sortOrder(), last.id()).encode();
        }
        return new MetadataViews.CategoryPage(items, more, next, version);
    }

    public List<MetadataViews.PathNode> path(long spaceId, long id) {
        List<CategoryRow> rows = mapper.path(spaceId, id);
        if (rows.isEmpty() || rows.size() > 8 || rows.getFirst().parentId() != null) {
            throw new MetadataException(UNAVAILABLE);
        }
        return rows.stream().map(row -> new MetadataViews.PathNode(row.id(), row.name())).toList();
    }
}
