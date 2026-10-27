package com.zhishiku.kb.worker.embedding;


/**
 * Worker 文档向量化配置。
 *
 * @param provider       向量实现名称
 * @param baseUrl        远程 embedding 服务地址
 * @param apiKey         远程服务密钥
 * @param model          embedding 模型名称
 * @param dimension      向量维度
 * @param batchSize      单批处理文档分块数
 * @param timeoutSeconds 单次请求超时时间，单位为秒
 */
public record EmbeddingProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int dimension,
        int batchSize,
        int timeoutSeconds) {

    public EmbeddingProperties {
        provider = provider == null || provider.isBlank() ? "local" : provider;
        model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        if (dimension <= 0) dimension = 1024;
        if (batchSize <= 0) batchSize = 16;
        if (timeoutSeconds <= 0) timeoutSeconds = 60;
    }
}
