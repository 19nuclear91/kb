package com.zhishiku.kb.app.parse.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 管理员重放死信请求。请求标识通过 {@code X-Request-Id} 传递。
 *
 * @param reason 重放原因，不得填写凭据或文档正文
 */
@Schema(name = "ParseDeadLetterReplayRequest", description = "重放解析死信请求")
public record ParseDeadLetterReplayRequest(
        @Schema(description = "重放原因，不要填写凭据或文档正文", example = "对象存储已恢复")
        @NotBlank String reason) {
}
