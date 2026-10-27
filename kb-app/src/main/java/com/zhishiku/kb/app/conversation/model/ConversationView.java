package com.zhishiku.kb.app.conversation.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 本人会话元数据
 *
 * @param id           会话 ID
 * @param title        标题
 * @param createdAt    创建时间
 * @param updatedAt    最近活动时间
 * @param lastTurnSeq  最后轮次序号
 * @param activeTurnId 活动轮次 ID
 */
@Schema(description = "本人会话元数据")
public record ConversationView(
        @Schema(description = "会话 ID") long id,
        @Schema(description = "标题") String title,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "最近活动时间") Instant updatedAt,
        @Schema(description = "最后轮次序号") long lastTurnSeq,
        @Schema(description = "活动轮次 ID") UUID activeTurnId) {
}

