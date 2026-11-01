package com.zhishiku.kb.app.document.metadata.service;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.common.security.PermissionScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.FORBIDDEN;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.NOT_FOUND;

@Component
public class MetadataAccess {
    private final AdminQueryAccess access;
    private final PermissionService permissions;
    private final DocumentMapper documents;
    private final SpaceQueryService spaces;
    private final SqlSessionTemplate sessions;

    public MetadataAccess(AdminQueryAccess access, PermissionService permissions, DocumentMapper documents,
                          SpaceQueryService spaces, SqlSessionTemplate sessions) {
        this.access = access;
        this.permissions = permissions;
        this.documents = documents;
        this.spaces = spaces;
        this.sessions = sessions;
    }

    public PermissionService.PrincipalHandle current() {
        sessions.clearCache();
        return access.current();
    }

    public record Managed(long actorId, SpaceRecord space) {
    }

    public record Editable(long actorId, DocumentSummary document) {
    }

    public Managed manager(long spaceId) {
        var actor = current();
        SpaceRecord space = spaces.findManaged(actor, spaceId);
        if (space == null) {
            throw new MetadataException(NOT_FOUND);
        }
        return new Managed(actor.userId(), space);
    }

    public long tagManager(boolean write) {
        var actor = current();
        Permission permission = write ? Permission.DOCUMENT_TAG_WRITE : Permission.DOCUMENT_TAG_READ;
        if (!actor.functionalPermissions().contains(permission.code())) {
            throw new MetadataException(FORBIDDEN);
        }
        return actor.userId();
    }

    public Editable document(long documentId, boolean edit) {
        var actor = current();
        DocumentSummary document = matches(documentId, permissions.resolve(actor, PermissionService.PERM_VIEW), PermissionService.PERM_VIEW);
        if (document == null || "archived".equals(document.getStatus())) {
            throw new MetadataException(NOT_FOUND);
        }
        if (edit && matches(documentId, permissions.resolve(actor, PermissionService.PERM_EDIT), PermissionService.PERM_EDIT) == null) {
            throw new MetadataException(FORBIDDEN);
        }
        return new Editable(actor.userId(), document);
    }

    public PermissionScope view() {
        return permissions.resolve(current(), PermissionService.PERM_VIEW);
    }

    private DocumentSummary matches(long id, PermissionScope scope, String permission) {
        if (scope.seesNothing()) {
            return null;
        }
        return documents.findSummary(id, scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                scope.clearanceLevel(), permission);
    }
}
