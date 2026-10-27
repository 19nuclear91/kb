package com.zhishiku.kb.common.conversation;

import lombok.Getter;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 多轮默认关闭；共享配置校验不依赖运行时框架。
 */
@Getter
@Setter
public class ConversationSettings {
    private boolean enabled;
    private String secret;
    private long signatureWindowSeconds = 60;
    private long nonceTtlSeconds = 180;
    private long leaseSeconds = 120;
    private long renewalMs = 30_000;
    private int historyPairs = 8;
    private int maxSources = 256;
    private int batchCodepoints = 1024;
    private long batchMs = 1000;
    private int maxAnswerCodepoints = 64_000;
    private int maxQuestionCodepoints = 8000;
    private int maxContextCodepoints = 24_000;
    private int maxOutputTokens = 2048;
    private int reservationTokens = 12_000;
    private long settlementRetrySeconds = 3600;
    private long settlementDedupSeconds = 86_400;
    private int workBatchSize = 20;
    private long cleanupTimeoutMs = 5000;

    public void validate(boolean production) {
        if (!enabled) return;
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || (production && (secret.toLowerCase(Locale.ROOT).contains("dev")
                || secret.toLowerCase(Locale.ROOT).contains("example")))) {
            throw new IllegalArgumentException("多轮服务签名密钥无效");
        }
        if (signatureWindowSeconds <= 0 || signatureWindowSeconds > 300
                || nonceTtlSeconds < 2 * signatureWindowSeconds + 1
                || leaseSeconds < 60 || renewalMs <= 0 || renewalMs >= leaseSeconds * 1000 / 2
                || historyPairs < 1 || historyPairs > 32 || maxSources < 1 || maxSources > 1024
                || batchCodepoints < 1 || batchMs < 1 || batchMs > 5000
                || maxAnswerCodepoints < batchCodepoints || maxAnswerCodepoints > 1_000_000
                || maxQuestionCodepoints < 1 || maxQuestionCodepoints > 8000
                || maxContextCodepoints < maxQuestionCodepoints || maxContextCodepoints > 100_000
                || maxOutputTokens < 1 || reservationTokens <= maxOutputTokens
                || settlementRetrySeconds < 1 || settlementDedupSeconds <= settlementRetrySeconds + 60
                || workBatchSize < 1 || workBatchSize > 100
                || cleanupTimeoutMs < 1 || cleanupTimeoutMs >= leaseSeconds * 1000) {
            throw new IllegalArgumentException("多轮期限或预算配置无效");
        }
    }
}

