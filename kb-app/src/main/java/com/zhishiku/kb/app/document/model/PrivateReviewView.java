package com.zhishiku.kb.app.document.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.zhishiku.kb.common.review.ReviewState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 仅经当次 view 和参与资格检查返回的私有审核轮次。
 *
 * @param reviewId 精确轮次标识
 * @param roundNo 轮次序号
 * @param state 轮次状态
 * @param submittedBy 本轮送审者标识
 * @param submittedAt 本轮送审时间
 * @param decidedAt 本轮决定时间
 * @param reason 私有纯文本原因
 */
@Schema(description = "私有审核轮次")
public record PrivateReviewView(
        @Schema(description = "轮次标识") UUID reviewId,
        @Schema(description = "轮次序号") int roundNo,
        @Schema(description = "轮次状态") ReviewState state,
        @Schema(description = "本轮送审者") @JsonSerialize(using = ToStringSerializer.class) long submittedBy,
        @Schema(description = "送审时间") Instant submittedAt,
        @Schema(description = "决定时间") Instant decidedAt,
        @Schema(description = "私有纯文本原因") String reason) {
    public static PrivateReviewView from(DocumentReviewRow row) {
        return new PrivateReviewView(row.getId(), row.getRoundNo(), row.getState(), row.getSubmittedBy(),
                row.getSubmittedAt(), row.getDecidedAt(), row.getReason());
    }
}
