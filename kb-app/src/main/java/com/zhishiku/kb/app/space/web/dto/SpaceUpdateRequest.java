package com.zhishiku.kb.app.space.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 更新空间元数据（名称、描述）。归档状态另有独立端点，不在此处变更。
 *
 * @param name        新空间名称
 * @param description 新空间描述
 * @param version     客户端读取到的空间版本
 * @param requestId   请求幂等标识
 */
@Schema(description = "更新空间元数据请求")
public record SpaceUpdateRequest(
        @Schema(description = "空间名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 200) String name,
        @Schema(description = "空间描述")
        @Size(max = 2000) String description,
        @Schema(description = "读取到的空间版本，用于乐观锁", requiredMode = Schema.RequiredMode.REQUIRED)
        @PositiveOrZero long version,
        @Schema(description = "请求标识，用于幂等去重")
        String requestId) {
}
