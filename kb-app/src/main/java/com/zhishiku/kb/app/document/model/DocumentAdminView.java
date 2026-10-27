package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 固定最小文档管理记录，不包含正文及阅读元数据。
 *
 * @param documentId    文档内部标识
 * @param spaceId       当前所属空间
 * @param spaceArchived 空间是否归档
 */
@Schema(description = "最小文档管理记录")
public record DocumentAdminView(
        @Schema(description = "文档内部标识") long documentId,
        @Schema(description = "当前所属空间标识") long spaceId,
        @Schema(description = "当前空间是否归档") boolean spaceArchived) {
}
