package com.zhishiku.kb.worker.telemetry;

import com.zhishiku.kb.worker.parse.FailureClassifier;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public final class TaskTelemetry {
    public static final TaskTelemetry NOOP = new TaskTelemetry(Tracer.NOOP, false);
    static final Set<String> ATTRIBUTES = Set.of("kb.task.id", "kb.version.id", "kb.execution.generation", "kb.failure.class");
    private final Tracer tracer;

    public TaskTelemetry(Tracer tracer, @Value("${kb.telemetry.tracing.enabled:false}") boolean enabled) {
        this.tracer = enabled ? tracer : Tracer.NOOP;
    }

    public TaskSpan start(ParseTask task) {
        Span span = tracer.spanBuilder().setNoParent().name("kb.parse.task")
                .tag("kb.task.id", String.valueOf(task.id())).tag("kb.version.id", String.valueOf(task.versionId()))
                .tag("kb.execution.generation", String.valueOf(task.executionGeneration())).start();
        return new TaskSpan(span, tracer.withSpan(span));
    }

    public TaskSpan stage(Stage stage) {
        Span span = tracer.nextSpan().name(stage.spanName).start();
        return new TaskSpan(span, tracer.withSpan(span));
    }

    public <T> T inStage(Stage stage, Work<T> work) throws Exception {
        try (var span = stage(stage)) {
            try {
                return work.run();
            } catch (Exception error) {
                span.failed(new FailureClassifier().classify(error));
                throw error;
            }
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T run() throws Exception;
    }

    public enum Stage {
        PARSE("kb.parse.document"), EMBEDDING("kb.parse.embedding"), PUBLISH("kb.parse.publish");

        private final String spanName;

        Stage(String spanName) {
            this.spanName = spanName;
        }
    }

    public static final class TaskSpan implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope scope;

        private TaskSpan(Span span, Tracer.SpanInScope scope) {
            this.span = span;
            this.scope = scope;
        }

        public void failed(FailureClassifier.Classification failure) {
            // 不接收异常或摘要，避免 OTel 自动记录 exception.message/stacktrace。
            span.tag("kb.failure.class", failure == null ? "UNKNOWN" : failure.metricClass());
        }

        @Override
        public void close() {
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
}
