package com.zhishiku.kb.worker.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 重建任务的租约、批次和重试配置。
 *
 * @param leaseSeconds 任务租约时长，单位秒
 * @param batchSize    单批次处理数量
 * @param maxAttempts  最大允许尝试次数
 */
@ConfigurationProperties("kb.embedding.rebuild")
public record EmbeddingRebuildProperties(@DefaultValue("300") int leaseSeconds,
                                         @DefaultValue("16") int batchSize,
                                         @DefaultValue("3") int maxAttempts) {
    public EmbeddingRebuildProperties {
        if (leaseSeconds < 30 || leaseSeconds > 3600 || batchSize < 1 || batchSize > 100
                || maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("invalid embedding rebuild limits");
        }
    }
}
