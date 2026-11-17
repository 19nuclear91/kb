package com.zhishiku.kb.app.document.changenote.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.changenote.mapper.ChangeNoteMapper;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;

import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.FORBIDDEN;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.NOT_FOUND;

/** actor 只允许由 web 层的已验证会话提供；全站拦截器保留登录和强制改密门禁。 */
@Service
public class ChangeNoteAccess {
    private final PermissionService permissions;
    private final DocumentVersionAccessService versions;
    private final ChangeNoteMapper mapper;
    private final SqlSessionTemplate sessions;

    public ChangeNoteAccess(PermissionService permissions, DocumentVersionAccessService versions, ChangeNoteMapper mapper,
                          SqlSessionTemplate sessions) {
        this.permissions = permissions;
        this.versions = versions;
        this.mapper = mapper;
        this.sessions = sessions;
    }

    public PermissionService.PrincipalHandle current(long actor) {
        sessions.clearCache();
        var handle = permissions.principalOf(actor);
        handle.requireEnabled();
        return handle;
    }

    public void version(PermissionService.PrincipalHandle handle, long document, long version) {
        var key = new SourceKey(document, version);
        if (!versions.visible(handle, Set.of(key), VersionAccessPurpose.INTERACTIVE).contains(key)) {
            throw new ChangeNoteException(NOT_FOUND);
        }
    }

    public boolean has(PermissionService.PrincipalHandle handle, long document, String permission) {
        var scope = permissions.resolve(handle, permission);
        return !scope.seesNothing() && mapper.hasPermission(document, scope.visibleSpaceIdList(),
                Arrays.asList(scope.principalKeys()), scope.clearanceLevel(), permission);
    }

    public void editable(PermissionService.PrincipalHandle handle, long document, long version) {
        version(handle, document, version);
        if (!has(handle, document, PermissionService.PERM_VIEW)) {
            throw new ChangeNoteException(NOT_FOUND);
        }
        if (!has(handle, document, PermissionService.PERM_EDIT)) {
            throw new ChangeNoteException(FORBIDDEN);
        }
    }
}
