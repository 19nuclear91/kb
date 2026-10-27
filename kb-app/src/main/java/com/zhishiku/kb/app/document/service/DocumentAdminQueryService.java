package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.PermissionService.PrincipalHandle;
import com.zhishiku.kb.app.document.mapper.DocumentAdminQueryMapper;
import com.zhishiku.kb.app.document.model.DocumentAdminView;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 最小管理查询与普通文档阅读查询独立。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class DocumentAdminQueryService {
    private final DocumentAdminQueryMapper mapper;
    private final AdminQueryAccess access;
    private final PermissionService permissions;

    public DocumentAdminQueryService(DocumentAdminQueryMapper mapper, AdminQueryAccess access,
                                     PermissionService permissions) {
        this.mapper = mapper;
        this.access = access;
        this.permissions = permissions;
    }

    public QueryPage<DocumentAdminView> list(Map<String, List<String>> input) {
        PrincipalHandle handle = access.current();
        var params = new QueryParameters(input, "spaceId", "documentId", "spaceArchived", "limit", "cursor");
        Long spaceId = params.positive("spaceId"), documentId = params.positive("documentId");
        Boolean archived = params.bool("spaceArchived");
        int limit = params.limit();
        var filters = QueryParameters.filters("spaceId", spaceId, "documentId", documentId, "spaceArchived", archived);
        var cursor = QueryCursor.decode(params.cursor(), "documents");
        if (cursor != null) cursor.verify("documents", filters);
        List<DocumentAdminView> rows = rows(handle, spaceId, documentId, archived, cursor == null ? null :
                cursor.id(), limit + 1);
        return QueryPage.slice(rows, limit, row -> QueryCursor.encode("documents", filters, row.documentId()));
    }

    /**
     * 仅供资源上下文管理入口使用，不向调用者暴露无资格文档的归属。
     */
    public long managedSpaceOf(PrincipalHandle handle, long documentId) {
        List<DocumentAdminView> rows = rows(handle, null, documentId, null, null, 1);
        if (rows.isEmpty()) throw QueryException.missing();
        return rows.get(0).spaceId();
    }

    private List<DocumentAdminView> rows(PrincipalHandle handle, Long spaceId, Long documentId,
                                         Boolean archived, Long afterId, int fetch) {
        handle.requireEnabled();
        boolean global = handle.functionalPermissions().contains(Permission.SPACE_MANAGE_ALL.code());
        List<Long> spaces = global ? List.of() :
                permissions.resolve(handle, PermissionService.PERM_MANAGE).visibleSpaceIdList();
        if (!global && spaces.isEmpty()) return List.of();
        return mapper.list(global, spaces, spaceId, documentId, archived, afterId, fetch);
    }
}
