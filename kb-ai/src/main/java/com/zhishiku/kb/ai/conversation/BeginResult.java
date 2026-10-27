package com.zhishiku.kb.ai.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 内部首次或重放结果
 *
 * @param conversationId 会话 ID
 * @param turnId         轮次 ID
 * @param turnSeq        轮次序号
 * @param status         生成状态
 * @param replayed       是否重放
 * @param execution      首次执行资格
 */
@Schema(description = "内部首次或重放结果")
public record BeginResult(
        @Schema(description = "会话 ID") long conversationId,
        @Schema(description = "轮次 ID") UUID turnId,
        @Schema(description = "轮次序号") long turnSeq,
        @Schema(description = "生成状态") String status,
        @Schema(description = "是否重放") boolean replayed,
        @Schema(description = "首次执行资格") ExecutionKey execution) {
}

