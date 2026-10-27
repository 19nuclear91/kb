package com.zhishiku.kb.ai.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 内部执行资格
 *
 * @param conversationId 会话 ID
 * @param turnId         轮次 ID
 * @param ownerUserId    稳定用户 ID
 * @param executionEpoch 删除栅栏
 * @param ticket         内部票据
 */
@Schema(description = "内部执行资格")
public record ExecutionKey(
        @Schema(description = "会话 ID") long conversationId,
        @Schema(description = "轮次 ID") UUID turnId,
        @Schema(description = "稳定用户 ID") long ownerUserId,
        @Schema(description = "删除栅栏") long executionEpoch,
        @Schema(description = "内部票据") String ticket) {
    @Override
    public String toString() {
        return "ExecutionKey[redacted]";
    }
}

