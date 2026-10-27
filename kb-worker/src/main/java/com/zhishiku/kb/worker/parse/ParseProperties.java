package com.zhishiku.kb.worker.parse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 运行参数。
 *
 * @param leaseSeconds           租约时长。<b>必须大于最慢文档的解析耗时</b>，
 *                               否则正在正常解析的任务会被其他实例判定为"持有者已死"而抢走，
 *                               造成重复解析与重复写块。心跳续租能缓解，但初始值仍应给足。
 * @param batchSize              单次领取任务数。P0 单线程顺序处理，给大了只是让租约白白消耗，
 *                               因为后面的任务要排在前面的后面等。
 * @param pollIntervalMs         轮询间隔。PG 队列是拉模型，这个值决定了任务延迟下限。
 * @param heartbeatMs            续租心跳间隔，应显著小于 {@code leaseSeconds}。
 * @param taskTimeoutSeconds     单个解析任务的硬超时；超时任务会取消执行并重新进入失败重试路径。
 * @param retryBaseDelayMs       可重试失败的指数退避基准毫秒数。
 * @param retryMaxDelayMs        单次退避的最大毫秒数。
 * @param retryJitterRatio       退避抖动比例，范围为 0 到 1。
 * @param metricsGaugeIntervalMs 队列 gauge 的采样间隔毫秒数。
 */
@ConfigurationProperties(prefix = "kb.worker")
public record ParseProperties(
        int leaseSeconds,
        int batchSize,
        long pollIntervalMs,
        long heartbeatMs,
        int taskTimeoutSeconds,
        long retryBaseDelayMs,
        long retryMaxDelayMs,
        double retryJitterRatio,
        long metricsGaugeIntervalMs) {

    public static final long DEFAULT_RETRY_BASE_DELAY_MS = 30_000L;
    public static final long DEFAULT_RETRY_MAX_DELAY_MS = 900_000L;
    public static final double DEFAULT_RETRY_JITTER_RATIO = 0.20d;
    public static final long DEFAULT_METRICS_GAUGE_INTERVAL_MS = 30_000L;

    /**
     * 兼容旧的直接构造方式。
     */
    public ParseProperties(int leaseSeconds, int batchSize, long pollIntervalMs, long heartbeatMs) {
        this(leaseSeconds, batchSize, pollIntervalMs, heartbeatMs, 900,
                DEFAULT_RETRY_BASE_DELAY_MS, DEFAULT_RETRY_MAX_DELAY_MS,
                DEFAULT_RETRY_JITTER_RATIO, DEFAULT_METRICS_GAUGE_INTERVAL_MS);
    }

    /**
     * 兼容已有配置测试和调用方。
     */
    public ParseProperties(int leaseSeconds, int batchSize, long pollIntervalMs,
                           long heartbeatMs, int taskTimeoutSeconds) {
        this(leaseSeconds, batchSize, pollIntervalMs, heartbeatMs, taskTimeoutSeconds,
                DEFAULT_RETRY_BASE_DELAY_MS, DEFAULT_RETRY_MAX_DELAY_MS,
                DEFAULT_RETRY_JITTER_RATIO, DEFAULT_METRICS_GAUGE_INTERVAL_MS);
    }

    public ParseProperties {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("kb.worker.lease-seconds 必须为正数");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("kb.worker.batch-size 必须为正数");
        }
        // 心跳间隔必须留出足够余量：网络抖动或一次 GC 停顿不该导致租约失守。
        // 取 1/3 是常规做法——允许连续丢两次心跳仍不丢租约。
        if (heartbeatMs * 3 > leaseSeconds * 1000L) {
            throw new IllegalArgumentException(
                    "kb.worker.heartbeat-ms 应小于 lease-seconds 的三分之一，"
                            + "否则一次 GC 停顿就可能丢掉租约导致任务被重复领取");
        }
        if (taskTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("kb.worker.task-timeout-seconds 必须为正数");
        }
        if (retryBaseDelayMs <= 0) {
            throw new IllegalArgumentException("kb.worker.retry-base-delay-ms 必须为正数");
        }
        if (retryMaxDelayMs < retryBaseDelayMs) {
            throw new IllegalArgumentException("kb.worker.retry-max-delay-ms 不能小于 retry-base-delay-ms");
        }
        if (!Double.isFinite(retryJitterRatio) || retryJitterRatio < 0 || retryJitterRatio > 1) {
            throw new IllegalArgumentException("kb.worker.retry-jitter-ratio 必须在 0 到 1 之间");
        }
        if (metricsGaugeIntervalMs <= 0) {
            throw new IllegalArgumentException("kb.worker.metrics-gauge-interval-ms 必须为正数");
        }
    }

    /**
     * 计算指定领取尝试的退避时长。jitterFactor 由调用方提供，范围为 -1 到 1，
     * 使核心计算可在没有随机源的单元测试中保持确定性。
     */
    public long retryDelayMillis(int attempt, double jitterFactor) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt 必须为正数");
        }
        if (!Double.isFinite(jitterFactor) || jitterFactor < -1 || jitterFactor > 1) {
            throw new IllegalArgumentException("jitterFactor 必须在 -1 到 1 之间");
        }
        long exponential = retryBaseDelayMs;
        for (int i = 1; i < attempt && exponential < retryMaxDelayMs; i++) {
            if (exponential > retryMaxDelayMs / 2) {
                exponential = retryMaxDelayMs;
            } else {
                exponential = Math.min(retryMaxDelayMs, exponential * 2);
            }
        }
        double multiplier = 1d + jitterFactor * retryJitterRatio;
        long jittered = Math.round(exponential * multiplier);
        return Math.max(1L, Math.min(retryMaxDelayMs, jittered));
    }
}
