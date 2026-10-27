package com.zhishiku.kb.ai.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 内部固定结算意图
 *
 * @param turnId           轮次 ID
 * @param userId           稳定用户 ID
 * @param departmentId     领取时部门 ID
 * @param leaseId          固定配额租约
 * @param eventId          固定审计事件
 * @param reservedTokens   预留额度
 * @param usageStatus      真实累计用量状态
 * @param promptTokens     真实输入用量或空
 * @param completionTokens 真实输出用量或空
 * @param chargedTokens    实际扣除额度
 * @param generationStatus 生成终态
 * @param intentDigest     固定意图摘要
 * @param finalizedAt      意图固定时间
 */
@Schema(description = "内部固定结算意图")
public record SettlementIntent(
        @Schema(description = "轮次 ID") UUID turnId,
        @Schema(description = "稳定用户 ID") long userId,
        @Schema(description = "领取时部门 ID") long departmentId,
        @Schema(description = "固定配额租约") UUID leaseId,
        @Schema(description = "固定审计事件") UUID eventId,
        @Schema(description = "预留额度") long reservedTokens,
        @Schema(description = "真实累计用量状态") String usageStatus,
        @Schema(description = "真实输入用量或空") Long promptTokens,
        @Schema(description = "真实输出用量或空") Long completionTokens,
        @Schema(description = "实际扣除额度") long chargedTokens,
        @Schema(description = "生成终态") String generationStatus,
        @Schema(description = "固定意图摘要") String intentDigest,
        @Schema(description = "意图固定时间") Instant finalizedAt) {
}

