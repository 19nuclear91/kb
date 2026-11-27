package com.zhishiku.kb.app.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 订阅派生的有界运行预算，不影响发布协议和个人偏好的业务含义。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kb.knowledge-subscriptions")
public class KnowledgeSubscriptionProperties {
    private boolean derivationEnabled = true;
    private long scanDelayMs = 5000;
    private int discoverLimit = 50;
    private int claimLimit = 50;
    private int recipientBatchSize = 100;
    private int maxBatchesPerEvent = 10;
    private int leaseSeconds = 60;
    private int backoffInitialSeconds = 30;
    private int backoffMaxSeconds = 900;
    private int catchupWeeks = 20;
    private long metricsDelayMs = 30000;

    @PostConstruct
    public void validate() {
        if (scanDelayMs < 1000 || scanDelayMs > 3600000 || discoverLimit < 1 || discoverLimit > 500
                || claimLimit < 1 || claimLimit > 500 || recipientBatchSize < 1 || recipientBatchSize > 500
                || maxBatchesPerEvent < 1 || maxBatchesPerEvent > 50 || leaseSeconds < 30 || leaseSeconds > 3600
                || backoffInitialSeconds < 1 || backoffMaxSeconds < backoffInitialSeconds || backoffMaxSeconds > 86400
                || catchupWeeks < 1 || catchupWeeks > 52 || metricsDelayMs < 1000 || metricsDelayMs > 3600000) {
            throw new IllegalStateException("kb.knowledge-subscriptions 配置超出允许范围");
        }
    }

    public int backoff(int attempts) {
        long delay = backoffInitialSeconds;
        for (int index = 0; index < attempts && delay < backoffMaxSeconds; index++) {
            delay = Math.min(delay * 2, backoffMaxSeconds);
        }
        return (int) Math.min(delay, backoffMaxSeconds);
    }
}
