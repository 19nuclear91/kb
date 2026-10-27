package com.zhishiku.kb.app.conversation.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * 本人轮次投影
 *
 * @param id               轮次 ID
 * @param turnSeq          轮次序号
 * @param question         本人原始问题
 * @param answer           可见的已保存回答
 * @param status           生成状态
 * @param answerKind       回答类型
 * @param visibility       回答可见性
 * @param message          通用状态说明
 * @param citations        本轮新材料引用
 * @param lastBatchSeq     已保存批次位置
 * @param savedCodepoints  已保存长度
 * @param usageStatus      真实用量状态
 * @param promptTokens     真实输入 token
 * @param completionTokens 真实输出 token
 * @param settlementState  结算状态
 * @param chargedTokens    结算扣除额度
 */
@Schema(description = "本人轮次投影")
public record TurnView(
        @Schema(description = "轮次 ID") UUID id,
        @Schema(description = "轮次序号") long turnSeq,
        @Schema(description = "本人原始问题") String question,
        @Schema(description = "可见的已保存回答") String answer,
        @Schema(description = "生成状态") String status,
        @Schema(description = "回答类型") String answerKind,
        @Schema(description = "回答可见性") String visibility,
        @Schema(description = "通用状态说明") String message,
        @Schema(description = "本轮新材料引用") List<CitationView> citations,
        @Schema(description = "已保存批次位置") long lastBatchSeq,
        @Schema(description = "已保存长度") int savedCodepoints,
        @Schema(description = "真实用量状态") String usageStatus,
        @Schema(description = "真实输入 token") Long promptTokens,
        @Schema(description = "真实输出 token") Long completionTokens,
        @Schema(description = "结算状态") String settlementState,
        @Schema(description = "结算扣除额度") Long chargedTokens) {
}

