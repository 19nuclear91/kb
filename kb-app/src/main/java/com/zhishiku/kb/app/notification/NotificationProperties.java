package com.zhishiku.kb.app.notification;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 通知派生的有界运行参数；扫描间隔由调度注解读取同前缀配置。 */
@ConfigurationProperties(prefix = "kb.notification")
public class NotificationProperties {
    /** 单批事务超时秒数；租约必须明显长于它，避免提交前被接管。 */
    public static final int BATCH_TIMEOUT_SECONDS = 10;

    private int discoverLimit = 50;
    private int claimLimit = 50;
    private int recipientBatchSize = 100;
    private int maxBatchesPerEvent = 10;
    private int leaseSeconds = 60;
    private long backoffInitialSeconds = 30;
    private long backoffMaxSeconds = 900;

    @PostConstruct
    public void validate() {
        if (discoverLimit < 1 || discoverLimit > 500 || claimLimit < 1 || claimLimit > 500
                || recipientBatchSize < 1 || recipientBatchSize > 500 || maxBatchesPerEvent < 1 || maxBatchesPerEvent > 100
                || leaseSeconds < BATCH_TIMEOUT_SECONDS * 3 || leaseSeconds > 3600
                || backoffInitialSeconds < 1 || backoffMaxSeconds < backoffInitialSeconds || backoffMaxSeconds > 86400)
            throw new IllegalStateException("kb.notification 配置超出允许范围");
    }

    /** 第 attempts+1 次失败后的等待：初始值逐次翻倍并封顶。 */
    public long backoffSeconds(int attempts) {
        long delay = backoffInitialSeconds;
        for (int i = 0; i < attempts && delay < backoffMaxSeconds; i++) delay *= 2;
        return Math.min(delay, backoffMaxSeconds);
    }

    public int getDiscoverLimit() {
        return discoverLimit;
    }

    public void setDiscoverLimit(int discoverLimit) {
        this.discoverLimit = discoverLimit;
    }

    public int getClaimLimit() {
        return claimLimit;
    }

    public void setClaimLimit(int claimLimit) {
        this.claimLimit = claimLimit;
    }

    public int getRecipientBatchSize() {
        return recipientBatchSize;
    }

    public void setRecipientBatchSize(int recipientBatchSize) {
        this.recipientBatchSize = recipientBatchSize;
    }

    public int getMaxBatchesPerEvent() {
        return maxBatchesPerEvent;
    }

    public void setMaxBatchesPerEvent(int maxBatchesPerEvent) {
        this.maxBatchesPerEvent = maxBatchesPerEvent;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public long getBackoffInitialSeconds() {
        return backoffInitialSeconds;
    }

    public void setBackoffInitialSeconds(long backoffInitialSeconds) {
        this.backoffInitialSeconds = backoffInitialSeconds;
    }

    public long getBackoffMaxSeconds() {
        return backoffMaxSeconds;
    }

    public void setBackoffMaxSeconds(long backoffMaxSeconds) {
        this.backoffMaxSeconds = backoffMaxSeconds;
    }
}
