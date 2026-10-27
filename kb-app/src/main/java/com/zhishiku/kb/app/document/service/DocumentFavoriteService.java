package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentFavoriteMapper;
import com.zhishiku.kb.app.document.model.DocumentFavoriteRow;
import com.zhishiku.kb.app.document.model.DocumentFavoriteViews;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 本人收藏服务；数据库失败不会被转换成空收藏。 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class DocumentFavoriteService {
    private static final String CURSOR_KIND = "document-favorites";
    private final AdminQueryAccess access;
    private final PermissionService permissions;
    private final DocumentFavoriteMapper mapper;

    public DocumentFavoriteService(AdminQueryAccess access, PermissionService permissions,
                                   DocumentFavoriteMapper mapper) {
        this.access = access;
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Transactional(timeout = 5)
    public DocumentFavoriteViews.State put(String id) {
        return guarded(() -> {
            var handle = access.current();
            handle.requireEnabled();
            long documentId = QueryParameters.positiveValue(id);
            var scope = permissions.resolve(handle, PermissionService.PERM_VIEW);
            if (scope.seesNothing() || mapper.put(handle.userId(), documentId, scope.visibleSpaceIdList(),
                    List.of(scope.principalKeys()), scope.clearanceLevel()) == null) {
                throw ApiException.notFound("文档不存在或当前不可查看");
            }
            return new DocumentFavoriteViews.State(documentId, true);
        });
    }

    @Transactional(timeout = 5)
    public DocumentFavoriteViews.State delete(String id) {
        return guarded(() -> {
            var handle = access.current();
            handle.requireEnabled();
            long documentId = QueryParameters.positiveValue(id);
            mapper.delete(handle.userId(), documentId);
            return new DocumentFavoriteViews.State(documentId, false);
        });
    }

    public DocumentFavoriteViews.Page list(Map<String, List<String>> input) {
        return guarded(() -> {
            var handle = access.current();
            handle.requireEnabled();
            var parameters = new QueryParameters(input, "limit", "cursor");
            int limit = parameters.limit();
            var filters = QueryParameters.filters("userId", handle.userId());
            var cursor = QueryCursor.decode(parameters.cursor(), CURSOR_KIND);
            if (cursor != null) cursor.verify(CURSOR_KIND, filters);
            var scope = permissions.resolve(handle, PermissionService.PERM_VIEW);
            if (scope.seesNothing()) return new DocumentFavoriteViews.Page(List.of(), false, null);
            var rows = mapper.list(handle.userId(), cursor == null ? null : cursor.id(), limit + 1,
                    scope.visibleSpaceIdList(), List.of(scope.principalKeys()), scope.clearanceLevel());
            boolean more = rows.size() > limit;
            var page = rows.subList(0, Math.min(limit, rows.size()));
            String next = more ? QueryCursor.encode(CURSOR_KIND, filters, page.getLast().favoriteId()) : null;
            return new DocumentFavoriteViews.Page(page.stream().map(DocumentFavoriteRow::view).toList(), more, next);
        });
    }

    public DocumentFavoriteViews.States states(List<Long> ids) {
        return guarded(() -> {
            var handle = access.current();
            handle.requireEnabled();
            validateIds(ids);
            var scope = permissions.resolve(handle, PermissionService.PERM_VIEW);
            if (scope.seesNothing()) return new DocumentFavoriteViews.States(List.of());
            var rows = mapper.states(handle.userId(), ids, scope.visibleSpaceIdList(),
                    List.of(scope.principalKeys()), scope.clearanceLevel());
            Map<Long, DocumentFavoriteViews.State> byId = new HashMap<>();
            for (var row : rows) {
                if (!ids.contains(row.documentId()) || byId.put(row.documentId(), row) != null) {
                    throw ApiException.unavailable("收藏状态暂不可用");
                }
            }
            return new DocumentFavoriteViews.States(ids.stream().filter(byId::containsKey).map(byId::get).toList());
        });
    }

    public static void validateIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size()) throw QueryException.invalid();
    }

    private <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException | QueryException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("收藏服务暂不可用");
        }
    }
}
