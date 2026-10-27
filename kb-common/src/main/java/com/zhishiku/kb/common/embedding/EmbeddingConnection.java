package com.zhishiku.kb.common.embedding;

import java.net.URI;

/**
 * 嵌入服务的内部连接参数，与模型语义身份分离。
 *
 * @param endpoint       服务访问端点
 * @param apiKey         服务认证密钥，仅供内部调用，不得写入日志
 * @param timeoutSeconds 请求超时时间，单位秒
 * @param batchSize      单批次处理数量
 */
public record EmbeddingConnection(String endpoint, String apiKey, int timeoutSeconds, int batchSize) {

    public EmbeddingConnection {
        if (timeoutSeconds <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("embedding timeoutSeconds and batchSize must be positive");
        }
        if (endpoint != null) {
            try {
                URI uri = URI.create(endpoint);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getRawQuery() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("embedding endpoint must be an HTTP(S) base URL");
            }
        }
    }

    @Override
    public String toString() {
        return "EmbeddingConnection[endpoint=<redacted>, apiKey=<redacted>, timeoutSeconds="
                + timeoutSeconds + ", batchSize=" + batchSize + "]";
    }
}
