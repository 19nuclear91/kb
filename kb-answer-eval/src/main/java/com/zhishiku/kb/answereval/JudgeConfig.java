package com.zhishiku.kb.answereval;

/** 裁判与被测配置分别读取，不存在业务凭据回退。 */
public record JudgeConfig(int schemaVersion, String configurationId, String targetId, String baseUrl,
                          boolean allowHttpLoopback, String apiKeyEnv, String model, String rubricVersion,
                          String outputTokenField, int windowTokens, int maxOutputTokens, int safetyMarginTokens,
                          double inputBytesPerToken, long maxEstimatedInputTokens, int maxRequests,
                          Integer connectTimeoutMs, Integer requestTimeoutMs, Integer batchTimeoutMs) {
    public JudgeConfig {
        Checks.require(schemaVersion == 1);
        Checks.id(configurationId);
        Checks.id(targetId);
        Endpoint.validate(baseUrl, allowHttpLoopback);
        Checks.require(apiKeyEnv != null && apiKeyEnv.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"));
        Checks.text(model, 255);
        Checks.require(JudgeViews.VERSION.equals(rubricVersion)
                && ("max_tokens".equals(outputTokenField) || "max_completion_tokens".equals(outputTokenField))
                && windowTokens > 0 && windowTokens <= 2_000_000 && maxOutputTokens > 0 && maxOutputTokens <= 65_536
                && safetyMarginTokens >= 0 && (long) maxOutputTokens + safetyMarginTokens < windowTokens
                && Double.isFinite(inputBytesPerToken) && inputBytesPerToken >= 0.1 && inputBytesPerToken <= 16
                && maxEstimatedInputTokens > 0 && maxEstimatedInputTokens <= 2_000_000_000L
                && maxRequests > 0 && maxRequests <= 2000);
        connectTimeoutMs = connectTimeoutMs == null ? 5000 : connectTimeoutMs;
        requestTimeoutMs = requestTimeoutMs == null ? 120_000 : requestTimeoutMs;
        batchTimeoutMs = batchTimeoutMs == null ? 600_000 : batchTimeoutMs;
        Checks.require(connectTimeoutMs > 0 && connectTimeoutMs <= 60_000 && requestTimeoutMs >= connectTimeoutMs
                && requestTimeoutMs <= 300_000 && batchTimeoutMs > 0 && batchTimeoutMs <= 600_000);
    }

    @Override
    public String toString() {
        return "JudgeConfig[redacted]";
    }
}
