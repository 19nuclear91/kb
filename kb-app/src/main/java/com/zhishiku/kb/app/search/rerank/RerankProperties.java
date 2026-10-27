package com.zhishiku.kb.app.search.rerank;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索结果重排配置。
 *
 * @param provider       重排实现，例如 {@code local}
 * @param baseUrl        远程重排服务地址
 * @param apiKey         远程服务密钥
 * @param model          重排模型名称
 * @param timeoutSeconds 单次重排超时时间，单位为秒
 */
@ConfigurationProperties(prefix = "kb.search.rerank")
public record RerankProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds) {

    public RerankProperties {
        provider = provider == null || provider.isBlank() ? "local" : provider;
        model = model == null || model.isBlank() ? "local-token-overlap-v1" : model;
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }
}
