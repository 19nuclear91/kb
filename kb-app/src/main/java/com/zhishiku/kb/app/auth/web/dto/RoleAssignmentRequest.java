package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 分配角色请求。
 *
 * @param roleId    角色内部 ID，必须存在于服务端角色表
 * @param requestId 幂等请求标识
 */
@Schema(name = "RoleAssignmentRequest", description = "分配角色请求")
public record RoleAssignmentRequest(
        @Schema(description = "角色内部 ID", example = "2")
        @NotNull @Positive Long roleId,
        @Schema(description = "同一操作域内的幂等请求标识")
        String requestId) {
}
