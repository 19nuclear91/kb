package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建部门请求。
 *
 * @param code      稳定部门编码
 * @param name      部门名称
 * @param parentId  父部门内部 ID；为空表示根部门
 * @param requestId 幂等请求标识
 */
@Schema(name = "DepartmentCreateRequest", description = "创建部门请求")
public record DepartmentCreateRequest(
        @Schema(description = "稳定部门编码", example = "platform") @NotBlank String code,
        @Schema(description = "部门名称", example = "平台组") @NotBlank String name,
        @Schema(description = "父部门内部 ID；根部门为空") Long parentId,
        @Schema(description = "同一操作域内的幂等请求标识", requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId) {
}
