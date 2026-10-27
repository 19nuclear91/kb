package com.zhishiku.kb.ai.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/ai/chat} 请求体。问题不再出现在 URL 和网关访问日志里。
 *
 * @param question 用户问题，长度上限为 8000 个字符
 */
@Schema(name = "ChatRequest", description = "AI 对话请求")
public record ChatRequest(
        @Schema(description = "用户问题", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "如何拆分微服务？")
        @NotBlank
        @Size(max = 8000, message = "问题长度不能超过 8000 个字符")
        String question) {
}
