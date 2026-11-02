package com.zhishiku.kb.app.telemetry;

import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.prometheus.metrics.tracer.common.SpanContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.NoopTracerAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 只装配观测与内存注册表，不创建 Web 服务或 OTLP 网络导出器。 */
class PrometheusExemplarConfigurationTest {
    private static final String OPEN_METRICS = "application/openmetrics-text;version=1.0.0;charset=utf-8";

    @Test
    void enabledTracingAttachesCurrentTraceToHistogramExemplar() {
        runner(true).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SpanContext.class);
            var registry = context.getBean(PrometheusMeterRegistry.class);
            var tracer = context.getBean(Tracer.class);
            var timer = Timer.builder("kb.model.call.duration").serviceLevelObjectives(Duration.ofSeconds(1)).register(registry);
            var span = tracer.nextSpan().name("test.exemplar").start();
            try (var scope = tracer.withSpan(span)) {
                timer.record(Duration.ofMillis(100));
            } finally {
                span.end();
            }
            assertThat(registry.scrape(OPEN_METRICS)).contains("kb_model_call_duration_seconds_bucket",
                    "trace_id=\"" + span.context().traceId() + "\"", "span_id=\"" + span.context().spanId() + "\"");
        });
    }

    @Test
    void disabledTracingStillExportsMetricsWithoutExemplars() {
        runner(false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("management.tracing.enabled", Boolean.class)).isFalse();
            var registry = context.getBean(PrometheusMeterRegistry.class);
            var timer = Timer.builder("kb.model.call.duration").serviceLevelObjectives(Duration.ofSeconds(1)).register(registry);
            Observation.createNotStarted("test.disabled", context.getBean(ObservationRegistry.class))
                    .observe(() -> {
                        assertThat(context.getBean(Tracer.class).currentSpan()).isSameAs(io.micrometer.tracing.Span.NOOP);
                        assertThat(org.slf4j.MDC.get("traceId")).isNull();
                        timer.record(Duration.ofMillis(100));
                        RestClient.builder().observationRegistry(context.getBean(ObservationRegistry.class))
                                .requestInterceptor((request, body, execution) -> {
                                    assertThat(request.getHeaders()).doesNotContainKeys("traceparent", "tracestate", "baggage");
                                    return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                                }).build().get().uri("http://internal.invalid/probe").retrieve().toBodilessEntity();
                    });
            assertThat(registry.scrape().lines()).contains("kb_model_call_duration_seconds_count 1");
            assertThat(registry.scrape(OPEN_METRICS)).contains("kb_model_call_duration_seconds_bucket")
                    .doesNotContain("trace_id", "span_id", " # {");
        });
    }

    @Test
    void disabledTracingDoesNotConfigureExemplarSpanContext() {
        runner(false).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(SpanContext.class)
                    .doesNotHaveBean(SdkTracerProvider.class).doesNotHaveBean(SpanExporter.class);
            assertThat(context.getBean(Tracer.class)).isSameAs(Tracer.NOOP);
        });
    }

    private static ApplicationContextRunner runner(boolean enabled) {
        return new ApplicationContextRunner().withUserConfiguration(TelemetryOnly.class)
                .withPropertyValues("kb.telemetry.tracing.enabled=" + enabled,
                        "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces")
                .withInitializer(context -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null));
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class,
            ObservationAutoConfiguration.class,
            org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
            OpenTelemetryAutoConfiguration.class, NoopTracerAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
            PrometheusExemplarsAutoConfiguration.class})
    static class TelemetryOnly {
    }
}
