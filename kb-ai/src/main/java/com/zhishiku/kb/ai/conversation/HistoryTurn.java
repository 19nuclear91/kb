package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.common.conversation.SourceKey;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * 服务端准备的合格历史
 *
 * @param id       轮次 ID
 * @param turnSeq  轮次序号
 * @param question 本人原始问题
 * @param answer   当前可见的完整回答
 * @param sources  完整来源依赖
 */
@Schema(description = "服务端准备的合格历史")
public record HistoryTurn(
        @Schema(description = "轮次 ID") UUID id,
        @Schema(description = "轮次序号") long turnSeq,
        @Schema(description = "本人原始问题") String question,
        @Schema(description = "当前可见的完整回答") String answer,
        @Schema(description = "完整来源依赖") List<SourceKey> sources) {
}

