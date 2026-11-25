package com.zhishiku.kb.app.auth.service.diagnostics;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentAdminQueryMapper;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import com.zhishiku.kb.common.security.IdentityException;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 仅真实操作者参与准入；返回的上下文不携带可执行身份句柄。 */
@Component
public class DiagnosticAccess {
    public record Resource(
            long actorId,
            String kind,
            long spaceId,
            Long documentId,
            boolean archived
    ) {
    }

    private final AdminQueryAccess access;
    private final PermissionService permissions;
    private final SpaceQueryService spaces;
    private final DocumentAdminQueryMapper documents;
    private final SqlSessionTemplate sessions;

    public DiagnosticAccess(AdminQueryAccess access, PermissionService permissions, SpaceQueryService spaces,
                            DocumentAdminQueryMapper documents, SqlSessionTemplate sessions) {
        this.access = access;
        this.permissions = permissions;
        this.spaces = spaces;
        this.documents = documents;
        this.sessions = sessions;
    }

    public Resource require(String kind, long resourceId, Long observationId, boolean changesManage) {
        sessions.clearCache();
        try {
            var actor = access.require(Permission.AUTHORIZATION_DIAGNOSE);
            long spaceId = resourceId;
            Long documentId = observationId;
            if ("DOCUMENT".equals(kind)) {
                documentId = resourceId;
            } else if (!"SPACE".equals(kind)) {
                throw new DiagnosticException(DiagnosticException.Reason.INVALID_INPUT);
            }
            if (documentId != null) {
                boolean global = actor.functionalPermissions().contains(Permission.SPACE_MANAGE_ALL.code());
                List<Long> managed = global ? List.of()
                        : permissions.resolve(actor, PermissionService.PERM_MANAGE).visibleSpaceIdList();
                var matches = documents.list(global, managed, "SPACE".equals(kind) ? resourceId : null,
                        documentId, null, null, 1);
                if (matches.isEmpty()) {
                    throw new DiagnosticException(DiagnosticException.Reason.NOT_FOUND);
                }
                spaceId = matches.getFirst().spaceId();
            }
            var space = spaces.findManaged(actor, spaceId);
            if (space == null) {
                throw new DiagnosticException(DiagnosticException.Reason.NOT_FOUND);
            }
            if (changesManage && !actor.functionalPermissions().contains(Permission.SPACE_GRANT_MANAGE.code())) {
                throw new DiagnosticException(DiagnosticException.Reason.FORBIDDEN);
            }
            return new Resource(actor.userId(), kind, spaceId, documentId, Boolean.TRUE.equals(space.getArchived()));
        } catch (QueryException error) {
            throw new DiagnosticException(error.status() == 404 ? DiagnosticException.Reason.NOT_FOUND
                    : DiagnosticException.Reason.FORBIDDEN);
        } catch (IdentityException error) {
            throw new DiagnosticException(error.httpStatus() == 401 ? DiagnosticException.Reason.UNAUTHORIZED
                    : DiagnosticException.Reason.FORBIDDEN);
        }
    }
}
