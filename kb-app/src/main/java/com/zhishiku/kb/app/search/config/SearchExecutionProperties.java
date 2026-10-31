package com.zhishiku.kb.app.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 搜索执行预算与实例级容量，时间单位为毫秒。
 *
 * @param totalTimeoutMs 总执行预算
 * @param embeddingTimeoutMs 查询向量阶段上限
 * @param retrievalTimeoutMs 数据库召回阶段上限
 * @param rerankTimeoutMs 重排阶段上限
 * @param responseReserveMs 响应准备预留
 * @param maxConcurrent 实例实际在途上限
 * @param maxConcurrentPerUser 每用户在本实例的实际在途上限
 * @param retryAfterSeconds 容量拒绝的重试建议秒数
 */
@ConfigurationProperties("kb.search.execution")
public record SearchExecutionProperties(
        @DefaultValue("8000") long totalTimeoutMs,
        @DefaultValue("2000") long embeddingTimeoutMs,
        @DefaultValue("3000") long retrievalTimeoutMs,
        @DefaultValue("1500") long rerankTimeoutMs,
        @DefaultValue("500") long responseReserveMs,
        @DefaultValue("8") int maxConcurrent,
        @DefaultValue("2") int maxConcurrentPerUser,
        @DefaultValue("1") int retryAfterSeconds) {

    public SearchExecutionProperties {
        for (long millis : new long[]{totalTimeoutMs, embeddingTimeoutMs, retrievalTimeoutMs,
                rerankTimeoutMs, responseReserveMs}) {
            if (millis <= 0 || millis > Long.MAX_VALUE / 1_000_000L) {
                throw new IllegalArgumentException("kb.search.execution 时间配置无效");
            }
        }
        long work = totalTimeoutMs - responseReserveMs;
        if (work <= 0 || embeddingTimeoutMs > work || retrievalTimeoutMs > work || rerankTimeoutMs > work) {
            throw new IllegalArgumentException("kb.search.execution 阶段预算或响应预留无效");
        }
        if (maxConcurrent <= 0 || maxConcurrentPerUser <= 0 || maxConcurrentPerUser > maxConcurrent
                || retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("kb.search.execution 容量或重试配置无效");
        }
    }

    public static SearchExecutionProperties defaults() {
        return new SearchExecutionProperties(8000, 2000, 3000, 1500, 500, 8, 2, 1);
    }
}
