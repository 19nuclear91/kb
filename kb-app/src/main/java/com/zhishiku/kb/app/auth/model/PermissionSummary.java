package com.zhishiku.kb.app.auth.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 服务端功能权限目录的对外摘要。
 *
 * @param code        功能权限编码，鉴权时按完整编码精确匹配
 * @param description 功能权限的用途说明
 */
@Schema(name = "PermissionSummary", description = "服务端定义的功能权限目录项")
public record PermissionSummary(
        @Schema(description = "功能权限编码，按完整编码精确匹配", example = "identity:role:read")
        String code,
        @Schema(description = "功能权限的用途说明", example = "查询角色与权限目录")
        String description) {
}
