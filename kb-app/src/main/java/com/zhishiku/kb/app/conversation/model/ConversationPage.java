package com.zhishiku.kb.app.conversation.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 会话分页
 *
 * @param items      会话列表
 * @param nextCursor 下一页游标
 */
@Schema(description = "会话分页")
public record ConversationPage(
        @Schema(description = "会话列表") List<ConversationView> items,
        @Schema(description = "下一页游标") String nextCursor) {
}

