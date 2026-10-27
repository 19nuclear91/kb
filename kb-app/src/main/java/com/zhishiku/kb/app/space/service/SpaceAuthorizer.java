package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.PermissionService.PrincipalHandle;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Component;

/**
 * 空间管理先校验启用身份，再按功能权限或目标空间 ACL 判定。
 */
@Component
public class SpaceAuthorizer {
    private final IdentityManagementAuthorizer identityAuthorizer;
    private final IdentityService identityService;
    private final PermissionService permissionService;

    public SpaceAuthorizer(IdentityManagementAuthorizer identityAuthorizer,
                           IdentityService identityService, PermissionService permissionService) {
        this.identityAuthorizer = identityAuthorizer;
        this.identityService = identityService;
        this.permissionService = permissionService;
    }

    public long requireSpaceManager(long spaceId) {
        PrincipalHandle handle = requireEnabledPrincipal();
        if (identityAuthorizer.has(handle, Permission.SPACE_MANAGE_ALL)) return handle.userId();
        var scope = permissionService.resolve(handle, PermissionService.PERM_MANAGE);
        if (!scope.visibleSpaceIdList().contains(spaceId)) {
            throw IdentityException.forbidden("无权管理该空间");
        }
        return handle.userId();
    }

    /**
     * manage 的授出与撤销不由跨空间管理权限隐式授予。
     */
    public long requireGrantManage() {
        return require(Permission.SPACE_GRANT_MANAGE);
    }

    public long requireCreate() {
        return require(Permission.SPACE_CREATE);
    }

    private long require(Permission permission) {
        long userId = requireEnabledUser();
        if (!has(permission)) throw IdentityException.forbidden("缺少功能权限：" + permission.code());
        return userId;
    }

    /**
     * 解析并校验启用身份，返回主体句柄供同一调用栈内复用。
     *
     * <p>与 {@link #requireEnabledUser()} 同源，区别是返回句柄，让
     * {@link #requireSpaceManager(long)} 的功能权限判定与 ACL 判定共用一次主体解析。
     */
    public PrincipalHandle requireEnabledPrincipal() {
        long userId = identityAuthorizer.requireLogin();
        // resolve 会把停用错误降级为 denyAll，故这里必须向句柄索取异常，
        // 而不是依赖降级后的空范围。
        PrincipalHandle handle = permissionService.principalOf(userId);
        handle.requireEnabled();
        return handle;
    }

    /**
     * 只解析身份；调用者还必须执行其操作所需的授权。
     */
    public long requireEnabledUser() {
        return requireEnabledPrincipal().userId();
    }

    public boolean has(Permission permission) {
        return identityAuthorizer.has(permission);
    }

    public void requireRoleReference(long roleId) {
        identityService.requireRoleReference(roleId);
    }
}
