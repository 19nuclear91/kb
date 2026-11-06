package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Component;

@Component
public class LifecycleAuthorizer {
    private final EpochSessionService sessions;
    private final RolePermissionMapper roles;

    public LifecycleAuthorizer(EpochSessionService sessions, RolePermissionMapper roles) {
        this.sessions = sessions;
        this.roles = roles;
    }

    public EpochSessionService.Session require(String token, Permission permission) {
        var session = sessions.check(token);
        if (session.mustChangePassword()) {
            throw IdentityException.passwordChangeRequired("请先修改密码");
        }
        java.util.List<String> codes;
        try {
            codes = roles.findUserPermissions(session.userId());
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("暂时无法确认操作资格");
        }
        if (codes == null || !codes.contains(permission.code())) {
            throw IdentityException.forbidden("缺少操作权限");
        }
        return session;
    }
}
