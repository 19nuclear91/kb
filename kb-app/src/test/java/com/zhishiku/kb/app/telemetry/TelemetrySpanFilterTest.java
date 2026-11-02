package com.zhishiku.kb.app.telemetry;

import io.micrometer.tracing.otel.bridge.CompositeSpanExporter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetrySpanFilterTest {
    @Test
    void failedObservationDoesNotExportRawExceptionPayload() {
        var output = InMemorySpanExporter.create();
        var exporter = new CompositeSpanExporter(List.of(output), List.of(), List.of(), List.of(new TelemetrySpanFilter()));
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracer = new io.micrometer.tracing.otel.bridge.OtelTracer(
                    OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("test"),
                    new io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext(), event -> { });
            var registry = io.micrometer.observation.ObservationRegistry.create();
            registry.observationConfig().observationHandler(new io.micrometer.tracing.handler.DefaultTracingObservationHandler(tracer));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> io.micrometer.observation.Observation
                    .createNotStarted("http.server.requests", registry).observe(() -> {
                        throw new IllegalStateException("secret-query https://internal.invalid?token=secret-token");
                    })).isInstanceOf(IllegalStateException.class);
            assertThat(output.getFinishedSpanItems()).singleElement().satisfies(span -> {
                assertThat(span.getAttributes().toString()).doesNotContain("secret-query", "secret-token");
                assertThat(span.getEvents().toString()).doesNotContain("secret-query", "secret-token");
                assertThat(span.getStatus().getDescription()).doesNotContain("secret-query", "secret-token");
                assertThat(span.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
                assertThat(span.getEvents()).singleElement().satisfies(event -> {
                    assertThat(event.getName()).isEqualTo("exception");
                    assertThat(event.getAttributes().size()).isEqualTo(1);
                    assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.type"))).isEqualTo("java.lang.IllegalStateException");
                });
            });
        }
    }

    @Test
    void exporterRemovesForbiddenAttributesAndPreservesSafeTypes() {
        var output = InMemorySpanExporter.create();
        var exporter = new CompositeSpanExporter(List.of(output), List.of(), List.of(), List.of(new TelemetrySpanFilter()));
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var span = OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("test")
                    .spanBuilder("safe-operation").startSpan();
            for (String key : List.of("http.url", "url.full", "http.request.header.authorization", "http.request.header.satoken", "net.peer.name",
                    "exception.message", "exception.stacktrace")) {
                span.setAttribute(key, "secret-canary");
            }
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "secret-status-description");
            span.addEvent("safe-stage", io.opentelemetry.api.common.Attributes.builder()
                    .put("safe.count", 3L).put("exception.stacktrace", "secret-stacktrace").build());
            span.setAttribute("http.route", "/api/search").setAttribute("safe.count", 3L).end();
            assertThat(output.getFinishedSpanItems()).singleElement().satisfies(data -> {
                assertThat(data.getAttributes().size()).isEqualTo(2);
                assertThat(data.getAttributes().get(AttributeKey.stringKey("http.route"))).isEqualTo("/api/search");
                assertThat(data.getAttributes().get(AttributeKey.longKey("safe.count"))).isEqualTo(3L);
                assertThat(data.getAttributes().toString()).doesNotContain("secret-canary");
                assertThat(data.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
                assertThat(data.getStatus().getDescription()).isEmpty();
                assertThat(data.getEvents()).singleElement().satisfies(event -> {
                    assertThat(event.getAttributes().size()).isEqualTo(1);
                    assertThat(event.getAttributes().get(AttributeKey.longKey("safe.count"))).isEqualTo(3L);
                });
            });
        }
    }
}
