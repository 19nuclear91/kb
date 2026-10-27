package com.zhishiku.kb.ai.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 公共重放仅返回身份和状态
 *
 * @param conversationId 会话 ID
 * @param turnId         轮次 ID
 * @param turnSeq        轮次序号
 * @param status         生成状态
 * @param replayed       是否重放
 */
@Schema(description = "公共重放仅返回身份和状态")
public record TurnReplay(
        @Schema(description = "会话 ID") long conversationId,
        @Schema(description = "轮次 ID") UUID turnId,
        @Schema(description = "轮次序号") long turnSeq,
        @Schema(description = "生成状态") String status,
        @Schema(description = "是否重放") boolean replayed) {
}

