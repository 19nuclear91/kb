package com.zhishiku.kb.ai.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务配置。
 *
 * @param appBaseUrl                {@code kb-app} 的基地址。语料只能从这里来。
 * @param retrieveTopK              送进 prompt 的分块数上限。
 * @param keepAliveMs               SSE 心跳间隔。见 {@code ChatController} 里关于为什么需要它的说明。
 * @param upstreamTimeout           上游模型调用超时（秒）。网关对 AI 路由不设响应超时，
 *                                  生命周期由这里控制——两边都不设就是个挂死连接的配方。
 * @param maxQuestionChars          单个问题允许的最大字符数。
 * @param maxContextChars           送入 ChatModelClient 的引用正文最大字符数。
 * @param requestsPerMinute         单个部门每分钟允许的请求数。
 * @param maxConcurrent             单个部门的并发对话上限。
 * @param tokenBudgetPerMinute      单个部门每分钟允许消耗的 token 总量。
 * @param maxTokensPerRequest       单个请求预留的 token 上限；结算时按模型实际 usage 多退少补。
 * @param quotaKeyPrefix            Redis 配额键前缀。
 * @param auditKey                  Redis 审计列表键。
 * @param auditMaxEvents            Redis 审计列表保留的最大事件数。
 * @param quotaLeaseSeconds         并发租约最长保留时间；应覆盖模型超时并留有余量。
 * @param quotaRenewalIntervalMs    并发租约续期间隔；必须小于租约时长。
 * @param redisRetryAttempts        可重试 Redis 操作的总尝试次数。
 * @param redisRetryBackoffMs       Redis 重试的初始退避毫秒数。
 * @param auditDeduplicationSeconds 审计事件幂等去重 key 的保留时间。
 */
@ConfigurationProperties(prefix = "kb.ai")
public record AiProperties(
        String appBaseUrl,
        int retrieveTopK,
        long keepAliveMs,
        int upstreamTimeout,
        int maxQuestionChars,
        int maxContextChars,
        int requestsPerMinute,
        int maxConcurrent,
        int tokenBudgetPerMinute,
        int maxTokensPerRequest,
        String quotaKeyPrefix,
        String auditKey,
        int auditMaxEvents,
        int quotaLeaseSeconds,
        long quotaRenewalIntervalMs,
        int redisRetryAttempts,
        long redisRetryBackoffMs,
        int auditDeduplicationSeconds) {

    /**
     * 兼容旧的直接构造方式。
     */
    public AiProperties(String appBaseUrl, int retrieveTopK, long keepAliveMs, int upstreamTimeout) {
        this(appBaseUrl, retrieveTopK, keepAliveMs, upstreamTimeout,
                8_000, 24_000, 60, 8,
                120_000, 12_000,
                "kb:ai:quota", "kb:ai:audit", 100_000, 120,
                30_000, 3, 100, 86_400);
    }

    /**
     * 兼容已有测试和直接构造调用方的完整旧签名。
     */
    public AiProperties(String appBaseUrl, int retrieveTopK, long keepAliveMs,
                        int upstreamTimeout, int maxQuestionChars, int maxContextChars,
                        int requestsPerMinute, int maxConcurrent) {
        this(appBaseUrl, retrieveTopK, keepAliveMs, upstreamTimeout,
                maxQuestionChars, maxContextChars, requestsPerMinute, maxConcurrent,
                120_000, 12_000,
                "kb:ai:quota", "kb:ai:audit", 100_000, 120,
                30_000, 3, 100, 86_400);
    }

    /**
     * 兼容上一版包含 Redis key 配置的直接构造方式。
     */
    public AiProperties(String appBaseUrl, int retrieveTopK, long keepAliveMs,
                        int upstreamTimeout, int maxQuestionChars, int maxContextChars,
                        int requestsPerMinute, int maxConcurrent,
                        String quotaKeyPrefix, String auditKey,
                        int auditMaxEvents, int quotaLeaseSeconds) {
        this(appBaseUrl, retrieveTopK, keepAliveMs, upstreamTimeout,
                maxQuestionChars, maxContextChars, requestsPerMinute, maxConcurrent,
                120_000, 12_000,
                quotaKeyPrefix, auditKey, auditMaxEvents, quotaLeaseSeconds,
                defaultRenewalInterval(quotaLeaseSeconds), 3, 100, 86_400);
    }

    /**
     * 兼容 token 配额版本的完整直接构造方式。
     */
    public AiProperties(String appBaseUrl, int retrieveTopK, long keepAliveMs,
                        int upstreamTimeout, int maxQuestionChars, int maxContextChars,
                        int requestsPerMinute, int maxConcurrent,
                        int tokenBudgetPerMinute, int maxTokensPerRequest,
                        String quotaKeyPrefix, String auditKey,
                        int auditMaxEvents, int quotaLeaseSeconds) {
        this(appBaseUrl, retrieveTopK, keepAliveMs, upstreamTimeout,
                maxQuestionChars, maxContextChars, requestsPerMinute, maxConcurrent,
                tokenBudgetPerMinute, maxTokensPerRequest,
                quotaKeyPrefix, auditKey, auditMaxEvents, quotaLeaseSeconds,
                defaultRenewalInterval(quotaLeaseSeconds), 3, 100, 86_400);
    }

    private static long defaultRenewalInterval(int leaseSeconds) {
        long leaseMillis = Math.max(1L, leaseSeconds * 1_000L);
        return Math.max(1L, Math.min(30_000L, leaseMillis / 3L));
    }

    public AiProperties {
        if (appBaseUrl == null || appBaseUrl.isBlank()) {
            throw new IllegalArgumentException("kb.ai.app-base-url 必须配置：AI 服务没有数据库连接，"
                    + "语料只能经由 kb-app 的授权检索接口获取");
        }
        if (retrieveTopK <= 0 || retrieveTopK > 200) {
            throw new IllegalArgumentException("kb.ai.retrieve-top-k 必须在 1-200 之间");
        }
        if (maxQuestionChars <= 0 || maxQuestionChars > 100_000) {
            throw new IllegalArgumentException("kb.ai.max-question-chars 必须在 1-100000 之间");
        }
        if (maxContextChars <= 0 || maxContextChars > 1_000_000) {
            throw new IllegalArgumentException("kb.ai.max-context-chars 必须在 1-1000000 之间");
        }
        if (requestsPerMinute <= 0 || maxConcurrent <= 0) {
            throw new IllegalArgumentException("kb.ai 配额参数必须为正数");
        }
        if (tokenBudgetPerMinute <= 0 || maxTokensPerRequest <= 0
                || maxTokensPerRequest > tokenBudgetPerMinute) {
            throw new IllegalArgumentException("kb.ai token 配额参数无效：单请求上限不能超过每分钟预算");
        }
        if (quotaKeyPrefix == null || quotaKeyPrefix.isBlank()
                || auditKey == null || auditKey.isBlank()) {
            throw new IllegalArgumentException("kb.ai 配额和审计 Redis key 必须配置");
        }
        if (auditMaxEvents <= 0 || quotaLeaseSeconds <= 0) {
            throw new IllegalArgumentException("kb.ai 审计保留数和配额租约必须为正数");
        }
        if (quotaRenewalIntervalMs <= 0
                || quotaRenewalIntervalMs >= quotaLeaseSeconds * 1_000L) {
            throw new IllegalArgumentException("kb.ai 配额续期间隔必须大于 0 且小于租约时长");
        }
        if (redisRetryAttempts <= 0 || redisRetryBackoffMs < 0) {
            throw new IllegalArgumentException("kb.ai Redis 重试参数无效");
        }
        if (auditDeduplicationSeconds <= 0) {
            throw new IllegalArgumentException("kb.ai 审计去重保留时间必须为正数");
        }
    }
}
