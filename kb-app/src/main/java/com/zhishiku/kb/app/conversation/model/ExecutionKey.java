package com.zhishiku.kb.app.conversation.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 内部执行资格，禁止进入公共投影。
 *
 * @param conversationId 会话 ID
 * @param turnId         轮次 ID
 * @param ownerUserId    创建执行资格的稳定用户
 * @param executionEpoch 删除栅栏
 * @param ticket         仅首次返回的随机票据
 */
@Schema(description = "内部轮次执行资格")
public record ExecutionKey(
        @Schema(description = "会话 ID") long conversationId,
        @Schema(description = "轮次 ID") UUID turnId,
        @Schema(description = "稳定用户 ID") long ownerUserId,
        @Schema(description = "删除栅栏") long executionEpoch,
        @Schema(description = "临时执行票据") String ticket) {
    @Override
    public String toString() {
        return "ExecutionKey[redacted]";
    }
}

