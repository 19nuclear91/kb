package com.zhishiku.kb.worker.telemetry;

import com.zhishiku.kb.worker.parse.FailureClassifier;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import io.micrometer.tracing.otel.bridge.CompositeSpanExporter;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTelemetryPrivacyTest {
    @Test
    void failedTaskOnlyExportsWhitelistedIdentityAndFailureCategory() {
        var output = InMemorySpanExporter.create();
        var exporter = new CompositeSpanExporter(List.of(output), List.of(), List.of(), List.of(new TelemetrySpanFilter()));
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("test");
            var tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> { });
            var telemetry = new TaskTelemetry(tracer, true);
            var task = new ParseTask(1L, 2L, 1, 3, OffsetDateTime.now(), "owner",
                    "secret-object-key", "secret-file-name", 1L, "application/pdf");
            try (var span = telemetry.start(task)) {
                tracer.currentSpan().tag("kb.object.key", task.objectKey());
                span.failed(new FailureClassifier().classify(new IllegalArgumentException("secret-parser-body")));
            }
            assertThat(output.getFinishedSpanItems()).singleElement().satisfies(span -> {
                assertThat(span.getAttributes().size()).isEqualTo(4);
                assertThat(span.getAttributes().asMap().keySet()).allMatch(key -> TaskTelemetry.ATTRIBUTES.contains(key.getKey()));
                assertThat(span.getAttributes().toString()).contains("PERMANENT").doesNotContain("secret-");
                assertThat(span.getEvents()).isEmpty();
            });
        }
    }
}
