package com.zhishiku.kb.ai.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次对话请求的审计摘要；不包含问题正文、文档正文或令牌原文。
 *
 * @param occurredAt           事件发生时间
 * @param userId               用户内部 ID
 * @param departmentId         用户主部门内部 ID
 * @param principalFingerprint 脱敏后的主体指纹
 * @param questionChars        问题字符数
 * @param status               对话结算状态
 * @param promptTokens         输入 token 数
 * @param completionTokens     输出 token 数
 * @param elapsedMs            请求耗时，单位为毫秒
 * @param eventId              审计事件唯一 ID
 */
public record ChatAuditEvent(
        Instant occurredAt,
        long userId,
        long departmentId,
        String principalFingerprint,
        int questionChars,
        String status,
        int promptTokens,
        int completionTokens,
        long elapsedMs,
        String eventId) {

    public ChatAuditEvent {
        if (occurredAt == null) occurredAt = Instant.now();
        if (eventId == null || eventId.isBlank()) eventId = UUID.randomUUID().toString();
    }

    /**
     * 兼容 token 账本版本的构造方式。
     */
    public ChatAuditEvent(Instant occurredAt, long userId, long departmentId,
                          String principalFingerprint, int questionChars, String status,
                          int promptTokens, int completionTokens, long elapsedMs) {
        this(occurredAt, userId, departmentId, principalFingerprint, questionChars,
                status, promptTokens, completionTokens, elapsedMs, UUID.randomUUID().toString());
    }

    /**
     * 兼容旧的内存审计调用方。
     */
    public ChatAuditEvent(Instant occurredAt, String principalFingerprint, int questionChars,
                          String status, int promptTokens, int completionTokens, long elapsedMs) {
        this(occurredAt, 0L, 0L, principalFingerprint, questionChars,
                status, promptTokens, completionTokens, elapsedMs, UUID.randomUUID().toString());
    }
}
