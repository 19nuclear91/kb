package com.zhishiku.kb.worker.embedding.execution;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/** 本地执行资源指标，与持久队列和重建快照分别计量。 */
public final class WorkerEmbeddingMetrics {
    private final MeterRegistry registry;

    public WorkerEmbeddingMetrics(WorkerExecutions executions, MeterRegistry registry) {
        this.registry = registry;
        for (WorkerExecutions.Channel channel : WorkerExecutions.Channel.values()) {
            Gauge.builder("kb.worker.embedding.execution.occupied", executions, value -> value.occupied(channel))
                    .tag("channel", channel.name()).register(registry);
            Gauge.builder("kb.worker.embedding.execution.lingering", executions, value -> value.lingering(channel))
                    .tag("channel", channel.name()).register(registry);
        }
        Map<WorkerExecution.Purpose, Map<WorkerEmbeddingFailure.Reason, Counter>> stops =
                new EnumMap<>(WorkerExecution.Purpose.class);
        for (WorkerExecution.Purpose purpose : WorkerExecution.Purpose.values()) {
            Map<WorkerEmbeddingFailure.Reason, Counter> reasons = new EnumMap<>(WorkerEmbeddingFailure.Reason.class);
            for (WorkerEmbeddingFailure.Reason reason : WorkerEmbeddingFailure.Reason.values()) {
                reasons.put(reason, registry.counter("kb.worker.embedding.execution.stops",
                        "purpose", purpose.name(), "reason", reason.name()));
            }
            stops.put(purpose, reasons);
        }
        // 预注册全部有限标签；截止线程只递增计数，不等调用或取消动作返回。
        executions.observeStops((purpose, reason) -> stops.get(purpose).get(reason).increment());
    }

    public <T> T observe(WorkerExecution execution, Supplier<T> action) {
        var sample = Timer.start(registry);
        String outcome = "INTERNAL_ERROR";
        try {
            T result = action.get();
            execution.check();
            outcome = "SUCCESS";
            return result;
        } catch (WorkerEmbeddingFailure failure) {
            outcome = failure.reason().name();
            throw failure;
        } finally {
            String purpose = execution.purpose().name();
            registry.counter("kb.worker.embedding.calls", "purpose", purpose, "outcome", outcome).increment();
            sample.stop(Timer.builder("kb.worker.embedding.call.duration")
                    .tag("purpose", purpose).tag("outcome", outcome)
                    .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(10),
                            Duration.ofSeconds(60), Duration.ofSeconds(300), Duration.ofSeconds(900))
                    .register(registry));
        }
    }
}
