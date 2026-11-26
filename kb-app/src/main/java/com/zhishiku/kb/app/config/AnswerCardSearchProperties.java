package com.zhishiku.kb.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 独立答案卡搜索容量；只允许在协议硬上限内调小。 */
@ConfigurationProperties("kb.answer-card.search")
public record AnswerCardSearchProperties(
        @DefaultValue("8")
        int maxConcurrent,
        @DefaultValue("2")
        int perUser,
        @DefaultValue("5000")
        int budgetMillis,
        @DefaultValue("262144")
        int responseBytes
) {
    public AnswerCardSearchProperties {
        if (maxConcurrent < 1 || maxConcurrent > 8 || perUser < 1 || perUser > 2 || perUser > maxConcurrent
                || budgetMillis < 1 || budgetMillis > 5000 || responseBytes < 1 || responseBytes > 262144) {
            throw new IllegalArgumentException("答案卡搜索配置超出协议范围");
        }
    }
}
