package com.zhishiku.kb.app.auth.model;

/**
 * 管理资格收缩的变更描述，用于从当前权限事实中排除本次操作影响的记录。
 *
 * <p>通过工厂方法表达停用用户、解除角色或移除权限；不涉及的字段为 {@code null}。
 *
 * @param disabledUserId    将被停用的用户内部 ID；为空表示不排除整个用户
 * @param unboundUserId     将被解除角色绑定的用户内部 ID；与 {@code unboundRoleId} 配套使用
 * @param unboundRoleId     将从指定用户解除的角色内部 ID；为空表示不解除角色
 * @param permissionRoleId  将被移除权限的角色内部 ID；与 {@code removedPermission} 配套使用
 * @param removedPermission 将从指定角色移除的功能权限编码；为空表示不移除权限
 */
public record ManagementChange(Long disabledUserId, Long unboundUserId, Long unboundRoleId,
                               Long permissionRoleId, String removedPermission) {
    public static ManagementChange unchanged() {
        return new ManagementChange(null, null, null, null, null);
    }

    public static ManagementChange disableUser(long userId) {
        return new ManagementChange(userId, null, null, null, null);
    }

    public static ManagementChange unbindRole(long userId, long roleId) {
        return new ManagementChange(null, userId, roleId, null, null);
    }

    public static ManagementChange revokePermission(long roleId, String permission) {
        return new ManagementChange(null, null, null, roleId, permission);
    }

    public boolean retains(UserRolePermission fact) {
        return (disabledUserId == null || disabledUserId != fact.userId())
                && (unboundUserId == null || unboundUserId != fact.userId() || unboundRoleId != fact.roleId())
                && (permissionRoleId == null || permissionRoleId != fact.roleId()
                || !removedPermission.equals(fact.permission()));
    }
}
