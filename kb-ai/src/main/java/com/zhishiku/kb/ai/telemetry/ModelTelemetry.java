package com.zhishiku.kb.ai.telemetry;

import com.zhishiku.kb.ai.model.ChatModelFailure;
import com.zhishiku.kb.ai.model.ModelEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
public final class ModelTelemetry {
    public static final ModelTelemetry NOOP = new ModelTelemetry(new CompositeMeterRegistry());
    private final MeterRegistry registry;

    public ModelTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    public Flux<ModelEvent> observe(Supplier<Flux<ModelEvent>> source) {
        return Flux.defer(() -> {
            var duration = Timer.start(registry);
            var firstText = Timer.start(registry);
            var seenText = new AtomicBoolean();
            var knownUsage = new AtomicBoolean();
            var finished = new AtomicBoolean();
            return Flux.defer(source).doOnNext(event -> {
                if (event instanceof ModelEvent.Delta delta && !delta.text().isEmpty() && seenText.compareAndSet(false, true)) {
                    firstText.stop(registry.timer("kb.model.first_text.delay"));
                }
                if (event instanceof ModelEvent.FinalUsage) {
                    knownUsage.set(true);
                }
            }).doOnComplete(() -> finish(duration, finished, knownUsage, Outcome.COMPLETED))
                    .doOnError(error -> finish(duration, finished, knownUsage, outcome(error)))
                    .doOnCancel(() -> finish(duration, finished, knownUsage, Outcome.CANCELLED));
        });
    }

    private void finish(Timer.Sample duration, AtomicBoolean finished, AtomicBoolean knownUsage, Outcome outcome) {
        if (finished.compareAndSet(false, true)) {
            registry.counter("kb.model.calls", "outcome", outcome.name()).increment();
            duration.stop(registry.timer("kb.model.call.duration", "outcome", outcome.name()));
            registry.counter("kb.model.usage", "status", knownUsage.get() ? "KNOWN" : "UNKNOWN").increment();
        }
    }

    private static Outcome outcome(Throwable error) {
        if (error instanceof TimeoutException) {
            return Outcome.TIMEOUT;
        }
        if (error instanceof ChatModelFailure failure) {
            return switch (failure.kind()) {
                case TIMEOUT -> Outcome.TIMEOUT;
                case OUTPUT_TRUNCATED, PAYLOAD_LIMIT, BUDGET_UNAVAILABLE -> Outcome.LIMIT;
                case PROTOCOL_INVALID, INCOMPLETE_STREAM, UNSUPPORTED_OUTPUT, ALREADY_SUBSCRIBED -> Outcome.PROTOCOL_ERROR;
                case HTTP_REJECTED, CONNECT_FAILED -> Outcome.UPSTREAM_ERROR;
            };
        }
        return Outcome.UPSTREAM_ERROR;
    }

    private enum Outcome {
        COMPLETED, UPSTREAM_ERROR, PROTOCOL_ERROR, LIMIT, TIMEOUT, CANCELLED
    }
}
