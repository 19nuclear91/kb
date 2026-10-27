package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 移动部门请求。
 *
 * @param parentId  目标父部门内部 ID；为空表示移动为根部门
 * @param version   客户端读取到的部门版本
 * @param requestId 幂等请求标识
 */
@Schema(name = "DepartmentMoveRequest", description = "移动部门请求")
public record DepartmentMoveRequest(
        @Schema(description = "目标父部门；为空表示移动为根部门") Long parentId,
        @Schema(description = "客户端读取到的部门版本", example = "3")
        @NotNull @PositiveOrZero Long version,
        @Schema(description = "幂等请求标识", requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId) {
}
