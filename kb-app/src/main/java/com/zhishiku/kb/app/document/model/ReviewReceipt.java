package com.zhishiku.kb.app.document.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 审核命令成功回执；无正文、意见、人员名单或文件信息。
 *
 * @param documentId 文档标识
 * @param versionId 精确版本标识
 * @param reviewId 审核轮次标识；刷新时可为空
 * @param roundNo 审核轮次序号；无轮次时为空
 * @param stateVersion 命令执行后的版本并发值
 * @param indexStatus 索引状态
 * @param policy 固定策略
 * @param reviewState 该轮次状态
 * @param publicationEvidence 发布证据
 * @param occurredAt 服务端执行时间
 */
@Schema(description = "版本审核命令回执")
public record ReviewReceipt(
        @Schema(description = "文档标识") @JsonSerialize(using = ToStringSerializer.class) long documentId,
        @Schema(description = "版本标识") @JsonSerialize(using = ToStringSerializer.class) long versionId,
        @Schema(description = "轮次标识") UUID reviewId,
        @Schema(description = "轮次序号") Integer roundNo,
        @Schema(description = "执行后并发版本") @JsonSerialize(using = ToStringSerializer.class) long stateVersion,
        @Schema(description = "索引状态") String indexStatus,
        @Schema(description = "固定发布策略") PublicationPolicy policy,
        @Schema(description = "审核轮次状态") ReviewState reviewState,
        @Schema(description = "发布证据") PublicationEvidence publicationEvidence,
        @Schema(description = "服务端执行时间") Instant occurredAt) {
}
