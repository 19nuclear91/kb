package com.zhishiku.kb.app.service.impl.health;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.service.health.HealthFailure;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import com.zhishiku.kb.common.security.PermissionScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 功能资格与空间管理合取，阅读范围独立用于适用期分支。 */
@Component
@RequiredArgsConstructor
public class HealthAccess {
    private final AdminQueryAccess access;
    private final SpaceQueryService spaces;
    private final PermissionService permissions;

    public PermissionScope require(long spaceId) {
        var actor = access.require(Permission.KNOWLEDGE_HEALTH_READ);
        var space = spaces.findManaged(actor, spaceId);
        if (space == null) {
            throw QueryException.missing();
        }
        if (Boolean.TRUE.equals(space.getArchived())) {
            throw new HealthFailure(409, "SPACE_ARCHIVED");
        }
        if (space.getArchived() == null) {
            throw HealthFailure.unavailable();
        }
        return permissions.resolve(actor, PermissionService.PERM_VIEW);
    }
}
