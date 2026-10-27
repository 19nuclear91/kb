package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.PermissionService.PrincipalHandle;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Component;

/**
 * 新查询统一校验当前会话、启用主体和强制改密状态。
 */
@Component
public class AdminQueryAccess {
    private final PermissionService permissions;
    private final IdentityDepartmentMapper identities;

    public AdminQueryAccess(PermissionService permissions, IdentityDepartmentMapper identities) {
        this.permissions = permissions;
        this.identities = identities;
    }

    public PrincipalHandle current() {
        long userId;
        try {
            StpUtil.checkLogin();
            userId = StpUtil.getLoginIdAsLong();
        } catch (SaTokenException | NumberFormatException ignored) {
            throw IdentityException.unauthorized("登录状态无效");
        }
        PrincipalHandle handle = permissions.principalOf(userId);
        try {
            handle.requireEnabled();
        } catch (IdentityException error) {
            if (error.httpStatus() == 404) throw QueryException.forbidden();
            throw error;
        }
        if (!Boolean.FALSE.equals(identities.findMustChangePasswordById(userId))) throw QueryException.forbidden();
        return handle;
    }

    public PrincipalHandle require(Permission permission) {
        PrincipalHandle handle = current();
        if (!handle.functionalPermissions().contains(permission.code())) throw QueryException.forbidden();
        return handle;
    }
}
