package com.zhishiku.kb.app.search.embedding;


/**
 * 查询向量生成配置。
 *
 * @param provider       向量实现，例如 {@code local}
 * @param baseUrl        远程 embedding 服务地址
 * @param apiKey         远程服务密钥
 * @param model          embedding 模型名称
 * @param dimension      向量维度
 * @param timeoutSeconds 单次请求超时时间，单位为秒
 */
public record QueryEmbeddingProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int dimension,
        int timeoutSeconds) {

    public QueryEmbeddingProperties {
        provider = provider == null || provider.isBlank() ? "local" : provider;
        model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        if (dimension <= 0) dimension = 1024;
        if (timeoutSeconds <= 0) timeoutSeconds = 60;
    }
}
