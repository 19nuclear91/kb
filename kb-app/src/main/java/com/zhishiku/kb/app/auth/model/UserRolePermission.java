package com.zhishiku.kb.app.auth.model;

/**
 * 启用用户的角色权限事实，保留角色来源以判断管理资格变更后的权限并集。
 *
 * @param userId     启用用户的内部 ID
 * @param roleId     用户绑定的角色内部 ID
 * @param permission 该角色绑定的功能权限编码
 */
public record UserRolePermission(long userId, long roleId, String permission) {
}
