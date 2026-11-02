package com.zhishiku.kb.app.telemetry;

import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/** 仅使用进程内跨度导出器，不注册全局 SDK，也不连接 OTLP。 */
public final class InMemoryTracing implements AutoCloseable {
    public final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    public final Tracer tracer;
    public final ObservationRegistry registry = ObservationRegistry.create();

    public InMemoryTracing() {
        var otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("test");
        var current = new OtelCurrentTraceContext();
        tracer = new OtelTracer(otel, current, event -> { },
                new OtelBaggageManager(current, java.util.List.of(), java.util.List.of()));
        var propagator = new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);
        registry.observationConfig().observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator), new DefaultTracingObservationHandler(tracer)));
    }

    @Override
    public void close() {
        provider.close();
    }
}
