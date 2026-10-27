package com.zhishiku.kb.app.space.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 删除空间请求。
 *
 * @param requestId 请求幂等标识
 */
@Schema(description = "删除空间请求")
public record SpaceDeleteRequest(
        @Schema(description = "请求标识，用于幂等去重")
        String requestId) {
}
