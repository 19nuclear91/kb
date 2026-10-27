package com.zhishiku.kb.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 对外返回的角色摘要。
 *
 * <p>角色取值永远来自服务端角色表；调用方只应引用返回的标识，不得自行声明角色。
 *
 * @param roleId 角色内部 ID
 * @param code   稳定角色编码，例如 ROLE_ADMIN
 * @param name   角色名称
 */
@Schema(name = "RoleSummary", description = "角色摘要")
public record RoleSummary(
        @Schema(description = "角色内部 ID", example = "1")
        long roleId,
        @Schema(description = "稳定角色编码", example = "ROLE_ADMIN")
        String code,
        @Schema(description = "角色名称", example = "全局管理员")
        String name) {
}
