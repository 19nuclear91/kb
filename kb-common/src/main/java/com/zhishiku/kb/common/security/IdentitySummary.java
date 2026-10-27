package com.zhishiku.kb.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 对外返回的用户身份摘要。
 *
 * <p>摘要只包含权限解析所需的服务端事实，不包含会话令牌和完整外部 subject。
 *
 * @param userId         用户内部 ID
 * @param username       登录用户名
 * @param displayName    展示名称
 * @param departmentId   主部门内部 ID
 * @param clearanceLevel 用户密级
 * @param enabled        用户是否启用
 * @param version        当前用户版本，用于并发更新
 */
@Schema(name = "IdentitySummary", description = "用户身份摘要")
public record IdentitySummary(
        @Schema(description = "用户内部 ID", example = "1")
        long userId,
        @Schema(description = "登录用户名", example = "alice")
        String username,
        @Schema(description = "展示名称", example = "Alice")
        String displayName,
        @Schema(description = "主部门内部 ID", example = "10")
        Long departmentId,
        @Schema(description = "用户密级：0 公开，1 内部，2 保密，3 机密", example = "1")
        short clearanceLevel,
        @Schema(description = "用户是否启用", example = "true")
        boolean enabled,
        @Schema(description = "当前用户版本，用于并发更新", example = "4")
        long version) {
}
