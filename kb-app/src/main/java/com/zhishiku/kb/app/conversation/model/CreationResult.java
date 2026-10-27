package com.zhishiku.kb.app.conversation.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 创建会话结果
 *
 * @param conversation 会话元数据
 * @param replayed     是否重放
 */
@Schema(description = "创建会话结果")
public record CreationResult(
        @Schema(description = "会话元数据") ConversationView conversation,
        @Schema(description = "是否重放") boolean replayed) {
}

