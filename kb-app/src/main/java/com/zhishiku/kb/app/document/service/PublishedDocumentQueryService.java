package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.PublishedDocumentMapper;
import com.zhishiku.kb.app.document.model.PublishedDocumentReference;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** 窄固定发布资料契约；调用方分别取得处理人、提问者或查看者当前身份后使用。 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class PublishedDocumentQueryService {
    private final PublishedDocumentMapper mapper;
    private final PermissionService permissions;

    public PublishedDocumentQueryService(PublishedDocumentMapper mapper, PermissionService permissions) {
        this.mapper = mapper;
        this.permissions = permissions;
    }

    public Optional<PublishedDocumentReference> reference(PermissionService.PrincipalHandle actor,
                                                         long documentId, long versionId) {
        return guarded(() -> {
            actor.requireEnabled();
            if (documentId <= 0 || versionId <= 0) {
                throw QueryException.invalid();
            }
            var scope = permissions.resolve(actor, PermissionService.PERM_VIEW);
            if (scope.seesNothing()) {
                return Optional.empty();
            }
            var row = mapper.find(documentId, versionId, scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                    scope.clearanceLevel());
            if (row == null) {
                return Optional.empty();
            }
            if (row.documentId() != documentId || row.versionId() != versionId || row.title() == null) {
                throw new IllegalStateException("Published reference is inconsistent");
            }
            return row.published() ? Optional.of(row) : Optional.empty();
        });
    }

    public List<PublishedDocumentReference> candidates(PermissionService.PrincipalHandle actor, String nameQuery,
                                                      Long afterId, int fetch) {
        return guarded(() -> {
            actor.requireEnabled();
            if (fetch < 1 || fetch > 101 || (afterId != null && afterId <= 0)) {
                throw QueryException.invalid();
            }
            String name = nameQuery == null ? null : nameQuery.strip();
            if (name != null && (name.codePointCount(0, name.length()) > 128 || name.indexOf('\0') >= 0)) {
                throw QueryException.invalid();
            }
            var scope = permissions.resolve(actor, PermissionService.PERM_VIEW);
            if (scope.seesNothing()) {
                return List.of();
            }
            var rows = mapper.candidates(QueryParameters.like(name == null || name.isEmpty() ? null : name), afterId, fetch,
                    scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()), scope.clearanceLevel());
            if (rows == null || rows.size() > fetch) {
                throw new IllegalStateException("Published candidate page is unavailable");
            }
            long previous = afterId == null ? 0 : afterId;
            for (var row : rows) {
                if (row == null || row.documentId() <= previous || row.versionId() <= 0 || row.title() == null || !row.published()) {
                    throw new IllegalStateException("Published candidate page is inconsistent");
                }
                previous = row.documentId();
            }
            return List.copyOf(rows);
        });
    }

    private static <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException | QueryException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("暂时无法确认发布资料访问权限");
        }
    }
}
