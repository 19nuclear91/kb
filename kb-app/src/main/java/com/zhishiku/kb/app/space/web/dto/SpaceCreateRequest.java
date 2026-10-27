package com.zhishiku.kb.app.space.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建空间请求。
 *
 * @param code        空间全局唯一编码
 * @param name        空间名称
 * @param description 空间描述
 * @param requestId   请求幂等标识
 */
@Schema(description = "创建空间请求")
public record SpaceCreateRequest(
        @Schema(description = "空间编码，全局唯一", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 64) String code,
        @Schema(description = "空间名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 200) String name,
        @Schema(description = "空间描述")
        @Size(max = 2000) String description,
        @Schema(description = "请求标识，用于幂等去重")
        String requestId) {
}
