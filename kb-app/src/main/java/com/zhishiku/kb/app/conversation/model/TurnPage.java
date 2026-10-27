package com.zhishiku.kb.app.conversation.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 轮次分页
 *
 * @param items      轮次列表
 * @param nextCursor 下一页序号
 */
@Schema(description = "轮次分页")
public record TurnPage(
        @Schema(description = "轮次列表") List<TurnView> items,
        @Schema(description = "下一页序号") Long nextCursor) {
}

