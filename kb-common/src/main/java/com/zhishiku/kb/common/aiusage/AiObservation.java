package com.zhishiku.kb.common.aiusage;

import com.zhishiku.kb.common.conversation.RequestSignature;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 版本化聊天元数据，不承载正文、名称或凭据。
 */
public final class AiObservation {
    public static final int VERSION = 1;
    public static final Set<String> MODES = Set.of("SINGLE", "MULTI");
    public static final Set<String> GENERATIONS = Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "REJECTED");
    public static final Set<String> SETTLEMENTS = Set.of("NOT_REQUIRED", "RESERVED", "PENDING", "SETTLED", "REVIEW");
    public static final Set<String> USAGES = Set.of("KNOWN", "UNKNOWN", "NOT_INVOKED");
    public static final Set<String> KINDS = Set.of("REGISTERED", "ACQUIRE_INTENDED", "RESERVED", "DISPATCHED", "FINALIZED", "SETTLED", "REVIEW", "QUOTA_REJECTED");
    public static final Set<String> RESULTS = Set.of("MODEL", "NO_RESULTS", "PLACEHOLDER", "QUOTA_REJECTED", "DEPENDENCY_UNAVAILABLE", "CANCELLED", "INTERRUPTED", "FAILED");

    private AiObservation() {
    }

    /**
     * 新调用意图，主体由接收端从会话复核。
     * @param version 协议版本
     * @param callId 调用身份
     * @param initiatedAt 原意图时间
     * @param mode 单轮或多轮
     * @param turnId 可空轮次身份
     */
    public record Registration(int version, UUID callId, Instant initiatedAt, String mode, UUID turnId) {
        public Registration {
            if (version != VERSION || callId == null || initiatedAt == null || mode == null || !MODES.contains(mode)
                    || ("MULTI".equals(mode) != (turnId != null))) throw invalid();
        }
    }

    /**
     * 固定生命周期事件；可空字段按阶段严格验证。
     * @param version 协议版本
     * @param callId 调用身份
     * @param eventId 固定事件身份
     * @param epoch 执行代次
     * @param kind 阶段
     * @param occurredAt 原事实发生时间
     * @param leaseId 仅内部使用的额度关联
     * @param reservedTokens 预留量
     * @param generationStatus 生成终态
     * @param resultCode 固定结果码
     * @param usageStatus 实际用量状态
     * @param promptTokens 确认输入量或空
     * @param completionTokens 确认输出量或空
     * @param plannedChargedTokens 原策略的固定计划额度
     * @param settlementStatus 独立结算状态
     */
    public record Event(int version, UUID callId, UUID eventId, long epoch, String kind, Instant occurredAt,
                        UUID leaseId, Long reservedTokens, String generationStatus, String resultCode,
                        String usageStatus, Long promptTokens, Long completionTokens, Long plannedChargedTokens,
                        String settlementStatus) {
        public Event {
            if (version != VERSION || callId == null || eventId == null || epoch < 1 || occurredAt == null
                    || kind == null || !KINDS.contains(kind) || (generationStatus != null && !GENERATIONS.contains(generationStatus))
                    || (resultCode != null && !RESULTS.contains(resultCode))
                    || (usageStatus != null && !USAGES.contains(usageStatus))
                    || (settlementStatus != null && !SETTLEMENTS.contains(settlementStatus))) throw invalid();
            for (Long amount : new Long[]{reservedTokens, promptTokens, completionTokens, plannedChargedTokens})
                if (amount != null && amount < 0) throw invalid();
            if ("KNOWN".equals(usageStatus) && (promptTokens == null || completionTokens == null)) throw invalid();
            if ("UNKNOWN".equals(usageStatus) && (promptTokens != null || completionTokens != null)) throw invalid();
            if ("NOT_INVOKED".equals(usageStatus)
                    && (!Long.valueOf(0).equals(promptTokens) || !Long.valueOf(0).equals(completionTokens))) throw invalid();
        }

        public String digest() {
            return RequestSignature.digest(String.join("|", List.of(Integer.toString(version), callId.toString(),
                    eventId.toString(), Long.toString(epoch), kind, occurredAt.toString(), String.valueOf(leaseId),
                    String.valueOf(reservedTokens), String.valueOf(generationStatus), String.valueOf(resultCode),
                    String.valueOf(usageStatus), String.valueOf(promptTokens), String.valueOf(completionTokens),
                    String.valueOf(plannedChargedTokens), String.valueOf(settlementStatus))));
        }
    }

    /**
     * 仅表示实际累计统计，不计算额度。
     * @param status 用量状态
     * @param prompt 输入量或空
     * @param completion 输出量或空
     */
    public record Usage(String status, Long prompt, Long completion) {
    }

    public static Usage usage(boolean dispatched, boolean confirmed, Long prompt, Long completion) {
        if (!dispatched) return new Usage("NOT_INVOKED", 0L, 0L);
        if (!confirmed) return new Usage("UNKNOWN", null, null);
        if (prompt == null || completion == null || prompt < 0 || completion < 0) throw invalid();
        return new Usage("KNOWN", prompt, completion);
    }

    public static UUID eventId(UUID callId, String phase) {
        return UUID.nameUUIDFromBytes(("ai-observation-v1|" + callId + "|" + phase)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static IllegalArgumentException invalid() {
        return new IllegalArgumentException("AI 元数据无效");
    }
}
