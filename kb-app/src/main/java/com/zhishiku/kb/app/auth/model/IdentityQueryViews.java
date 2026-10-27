package com.zhishiku.kb.app.auth.model;

import com.zhishiku.kb.common.security.PrincipalType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 身份查询专用最小投影，不能携带内部凭据字段。
 */
public final class IdentityQueryViews {
    private IdentityQueryViews() {
    }

    /**
     * 可供解绑引用的外部绑定摘要。
     *
     * @param identityId 绑定记录标识
     * @param userId     所属用户标识
     * @param provider   提供方标识
     * @param enabled    是否启用
     */
    @Schema(description = "脱敏外部身份绑定")
    public record ExternalBinding(
            @Schema(description = "绑定记录标识") long identityId,
            @Schema(description = "所属用户标识") long userId,
            @Schema(description = "认证提供方标识") String provider,
            @Schema(description = "绑定是否启用") boolean enabled) {
    }

    /**
     * 在资源管理上下文中可查询的最小授权目标。
     *
     * @param principalType 主体类型
     * @param principalId   主体内部标识
     * @param label         主体显示标签
     */
    @Schema(description = "最小授权候选")
    public record PrincipalOption(
            @Schema(description = "USER、ROLE 或 DEPT") PrincipalType principalType,
            @Schema(description = "主体内部标识") long principalId,
            @Schema(description = "显示标签，不含用户名或身份关系") String label) {
    }

    /**
     * 已授权读取场景中的用户显示标签，不含用户名、部门、密级、角色或外部绑定。
     *
     * @param userId 用户内部标识
     * @param label  当前显示标签
     */
    public record UserLabel(long userId, String label) {
    }
}
