package com.zhishiku.kb.app.auth.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 对外返回的角色详情，权限读取与鉴权使用同一绑定表。
 *
 * @param roleId      角色内部 ID
 * @param code        稳定角色编码，创建后不可修改
 * @param name        角色显示名
 * @param permissions 角色当前绑定的功能权限编码；新建角色为空列表
 */
@Schema(name = "RoleDetail", description = "角色详情及当前绑定的功能权限")
public record RoleDetail(
        @Schema(description = "角色内部 ID", example = "1")
        long roleId,
        @Schema(description = "稳定角色编码，创建后不可修改", example = "ROLE_ADMIN")
        String code,
        @Schema(description = "角色显示名", example = "系统管理员")
        String name,
        @Schema(description = "角色当前绑定的功能权限编码；新建角色为空列表",
                example = "[\"identity:role:read\", \"identity:role:write\"]")
        List<String> permissions) {
}
