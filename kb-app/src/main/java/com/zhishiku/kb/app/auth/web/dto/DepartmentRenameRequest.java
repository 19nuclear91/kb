package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 重命名部门请求。
 *
 * @param name      新部门名称
 * @param version   客户端读取到的部门版本
 * @param requestId 幂等请求标识
 */
@Schema(name = "DepartmentRenameRequest", description = "重命名部门请求")
public record DepartmentRenameRequest(
        @Schema(description = "新部门名称") @NotBlank String name,
        @Schema(description = "客户端读取到的部门版本", example = "3")
        @NotNull @PositiveOrZero Long version,
        @Schema(description = "幂等请求标识", requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId) {
}
