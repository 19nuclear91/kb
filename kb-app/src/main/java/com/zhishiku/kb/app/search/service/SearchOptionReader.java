package com.zhishiku.kb.app.search.service;

import com.zhishiku.kb.app.search.mapper.SearchOptionMapper;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchOptions;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** 必须在调用方的有预算只读快照内读取，目录本身不提供额外授权。 */
@Service
public class SearchOptionReader {
    private final SearchOptionMapper mapper;

    public SearchOptionReader(SearchOptionMapper mapper) {
        this.mapper = mapper;
    }

    public SearchOptions.CategoryPage categories(long userId, SearchOptions.CategoryQuery query, PermissionScope scope) {
        String context = SearchOptionCursor.context("CATEGORY", userId, query.spaceId(), query.parentId(), null);
        var cursor = SearchOptionCursor.decode(query.cursor(), context);
        var principals = List.of(scope.principalKeys());
        if (scope.seesNothing() || !mapper.categoryContext(query.spaceId(), query.parentId(), scope.visibleSpaceIdList(), principals, scope.clearanceLevel())) {
            return new SearchOptions.CategoryPage(List.of(), false, null, 0);
        }
        Long version = mapper.treeVersion(query.spaceId());
        if (version == null || version < 0) {
            throw new SearchFailure(SearchFailure.Kind.DEPENDENCY);
        }
        if (cursor != null && cursor.treeVersion() != version) {
            throw ApiException.conflict("分类目录已变化，请重新读取");
        }
        var rows = mapper.categories(query.spaceId(), query.parentId(), cursor == null ? null : cursor.sortOrder(),
                cursor == null ? null : cursor.id(), query.limit() + 1, scope.visibleSpaceIdList(), principals, scope.clearanceLevel());
        boolean more = rows.size() > query.limit();
        var slice = rows.subList(0, Math.min(query.limit(), rows.size()));
        var paths = new HashMap<Long, List<SearchOptions.PathRow>>();
        if (!slice.isEmpty()) {
            for (var row : mapper.paths(query.spaceId(), slice.stream().map(SearchOptions.CategoryRow::id).toList())) {
                paths.computeIfAbsent(row.leafId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        var items = slice.stream().map(row -> project(row, paths.get(row.id()))).toList();
        String next = null;
        if (more) {
            var last = slice.getLast();
            next = new SearchOptionCursor(context, version, last.sortOrder(), last.id()).encode();
        }
        return new SearchOptions.CategoryPage(items, more, next, version);
    }

    public SearchOptions.TagPage tags(long userId, SearchOptions.TagQuery query, PermissionScope scope) {
        String context = SearchOptionCursor.context("TAG", userId, null, null, query.nameQuery());
        var cursor = SearchOptionCursor.decode(query.cursor(), context);
        if (cursor != null && (cursor.treeVersion() != 0 || cursor.sortOrder() != 0)) {
            throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
        }
        if (scope.seesNothing()) {
            return new SearchOptions.TagPage(List.of(), false, null);
        }
        var rows = mapper.tags(pattern(query.nameQuery()), cursor == null ? null : cursor.id(), query.limit() + 1,
                scope.visibleSpaceIdList(), List.of(scope.principalKeys()), scope.clearanceLevel());
        boolean more = rows.size() > query.limit();
        var slice = rows.subList(0, Math.min(query.limit(), rows.size()));
        String next = more ? new SearchOptionCursor(context, 0, 0, slice.getLast().id()).encode() : null;
        return new SearchOptions.TagPage(slice, more, next);
    }

    private static SearchOptions.Category project(SearchOptions.CategoryRow row, List<SearchOptions.PathRow> path) {
        if (path == null || path.isEmpty() || path.size() > 8 || path.getFirst().parentId() != null
                || path.getLast().id() != row.id() || path.stream().map(SearchOptions.PathRow::id).distinct().count() != path.size()) {
            throw new SearchFailure(SearchFailure.Kind.DEPENDENCY);
        }
        return new SearchOptions.Category(row.id(), row.parentId(), row.name(),
                path.stream().map(node -> new SearchOptions.PathNode(node.id(), node.name())).toList(), row.sortOrder());
    }

    private static String pattern(String value) {
        return value == null ? null : "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
