package com.zhishiku.kb.app.space.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 归档或取消归档请求。
 *
 * @param version   客户端读取到的空间版本
 * @param requestId 请求幂等标识
 */
@Schema(description = "空间归档状态变更请求")
public record SpaceArchiveRequest(
        @Schema(description = "读取到的空间版本，用于乐观锁", requiredMode = Schema.RequiredMode.REQUIRED)
        @PositiveOrZero long version,
        @Schema(description = "请求标识，用于幂等去重")
        String requestId) {
}
