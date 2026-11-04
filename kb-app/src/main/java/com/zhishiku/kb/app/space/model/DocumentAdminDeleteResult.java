package com.zhishiku.kb.app.space.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理端整文档删除的回执。
 *
 * <p>只含定位字段：标题、文件名与存储键不进入回执，与管理文档清单的最小投影一致。
 *
 * @param documentId 被删文档 ID
 * @param spaceId    删除时文档所属空间 ID
 * @param replayed   是否为同一请求标识的重放回执
 */
@Schema(description = "管理端整文档删除的首次回执或幂等重放回执。")
public record DocumentAdminDeleteResult(
        @Schema(description = "被删文档 ID") long documentId,
        @Schema(description = "删除时文档所属空间 ID") long spaceId,
        @Schema(description = "是否为同一请求标识的重放回执") boolean replayed) {
}
