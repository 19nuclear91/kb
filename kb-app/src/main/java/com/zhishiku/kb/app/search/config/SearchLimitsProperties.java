package com.zhishiku.kb.app.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 搜索载荷的实际字节限界。
 *
 * @param maxQueryCodepoints 查询 code point 上限
 * @param maxRequestBytes HTTP 请求体上限
 * @param maxEmbeddingResponseBytes 查询向量响应上限
 * @param maxRerankResponseBytes 重排响应上限
 * @param maxRerankRequestBytes 重排请求上限
 * @param maxResponseBytes 搜索成功响应上限
 */
@ConfigurationProperties("kb.search.limits")
public record SearchLimitsProperties(
        @DefaultValue("8000") int maxQueryCodepoints,
        @DefaultValue("131072") int maxRequestBytes,
        @DefaultValue("1048576") int maxEmbeddingResponseBytes,
        @DefaultValue("262144") int maxRerankResponseBytes,
        @DefaultValue("2097152") int maxRerankRequestBytes,
        @DefaultValue("4194304") int maxResponseBytes) {

    public SearchLimitsProperties {
        for (int value : new int[]{maxQueryCodepoints, maxRequestBytes, maxEmbeddingResponseBytes,
                maxRerankResponseBytes, maxRerankRequestBytes, maxResponseBytes}) {
            if (value <= 0 || value == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("kb.search.limits 配置必须为有界正整数");
            }
        }
    }

    public static SearchLimitsProperties defaults() {
        return new SearchLimitsProperties(8000, 131072, 1048576, 262144, 2097152, 4194304);
    }
}
