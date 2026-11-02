package com.zhishiku.kb.ai.telemetry;

import com.zhishiku.kb.ai.chat.ChatEvent;
import com.zhishiku.kb.ai.model.ChatModelFailure;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
public final class StreamTelemetry {
    public static final StreamTelemetry NOOP = new StreamTelemetry(new CompositeMeterRegistry());
    private static final Object CONTEXT_KEY = new Object();
    private final MeterRegistry registry;

    public StreamTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    public Flux<ServerSentEvent<Object>> observe(Supplier<Flux<ServerSentEvent<Object>>> source) {
        return Flux.defer(() -> {
            var run = new Run();
            registry.counter("kb.chat.started").increment();
            return Flux.defer(source).doOnNext(run::event)
                    .doOnError(error -> {
                        run.failed(error);
                        run.finish(run.timeout ? "TIMEOUT" : "ERROR", "UNKNOWN");
                    }).doOnComplete(() -> run.finish("COMPLETED", "NONE"))
                    .doOnCancel(() -> run.finish("CANCELLED", "NONE"))
                    .contextWrite(context -> context.put(CONTEXT_KEY, run));
        });
    }

    /** 在业务把异常转换为安全 SSE 之前保存固定分类，不保存异常或正文。 */
    public static <T> Flux<T> captureFailures(Flux<T> source) {
        return source.transformDeferredContextual((events, context) -> events.doOnError(error -> {
            Run run = context.getOrDefault(CONTEXT_KEY, null);
            if (run != null) {
                run.failed(error);
            }
        }));
    }

    private final class Run {
        private final Timer.Sample duration = Timer.start(registry);
        private final Timer.Sample first = Timer.start(registry);
        private final AtomicBoolean seenContent = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean timeout;

        private void failed(Throwable error) {
            if (error instanceof TimeoutException || error instanceof ChatModelFailure failure
                    && failure.kind() == ChatModelFailure.Kind.TIMEOUT) {
                timeout = true;
            }
        }

        private void event(ServerSentEvent<Object> event) {
            if (("delta".equals(event.event()) || "citation".equals(event.event())) && seenContent.compareAndSet(false, true)) {
                first.stop(registry.timer("kb.chat.first_event.delay"));
            }
            if ("done".equals(event.event())) {
                finish("COMPLETED", "NONE");
            } else if ("error".equals(event.event())) {
                Object code = event.data() instanceof ChatEvent.Error error ? error.code()
                        : event.data() instanceof Map<?, ?> data ? data.get("code") : null;
                String safeCode = "SEARCH_UNAVAILABLE".equals(code) ? "SEARCH_UNAVAILABLE"
                        : "INPUT_BUDGET_EXCEEDED".equals(code) ? "INPUT_BUDGET_EXCEEDED" : "UNKNOWN";
                finish(timeout ? "TIMEOUT" : "ERROR", safeCode);
            }
        }

        private void finish(String outcome, String code) {
            if (finished.compareAndSet(false, true)) {
                registry.counter("kb.chat.streams", "outcome", outcome, "error_code", code).increment();
                duration.stop(registry.timer("kb.chat.stream.duration", "outcome", outcome, "error_code", code));
            }
        }
    }
}
