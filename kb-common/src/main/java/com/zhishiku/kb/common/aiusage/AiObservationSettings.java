package com.zhishiku.kb.common.aiusage;

import lombok.Getter;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 无框架的配置值；各服务绑定后执行同一语义校验。
 */
@Getter
@Setter
public class AiObservationSettings {
    private boolean enabled;
    private String secret = "";
    private int retentionDays = 180;
    private boolean cleanupEnabled = true;
    private int cleanupBatchSize = 500;
    private int timeoutSeconds = 5;
    private int retryAttempts = 3;
    private int signatureWindowSeconds = 60;
    private int nonceTtlSeconds = 180;
    private int registrationWindowSeconds = 300;
    private int leaseSeconds = 180;
    private long renewalMs = 30000;

    public void validate(boolean production) {
        if (retentionDays < 1 || retentionDays > 36500 || cleanupBatchSize < 1 || cleanupBatchSize > 5000
                || timeoutSeconds < 1 || timeoutSeconds > 60 || retryAttempts < 1 || retryAttempts > 5
                || signatureWindowSeconds < 1 || signatureWindowSeconds > 300
                || nonceTtlSeconds < signatureWindowSeconds * 2 || nonceTtlSeconds > 3600
                || registrationWindowSeconds < signatureWindowSeconds || registrationWindowSeconds > 3600
                || leaseSeconds < 30 || leaseSeconds > 3600 || renewalMs < 1000 || renewalMs * 2 >= leaseSeconds * 1000L)
            throw new IllegalStateException("AI 观测配置无效");
        if ((enabled || (secret != null && !secret.isEmpty())) && (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || (production && (secret.toLowerCase(Locale.ROOT).contains("dev")
                || secret.toLowerCase(Locale.ROOT).contains("example")))))
            throw new IllegalStateException("AI 观测服务密钥无效");
    }
}
