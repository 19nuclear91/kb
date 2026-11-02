package com.zhishiku.kb.worker.telemetry;

import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.prometheus.metrics.tracer.common.SpanContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class PrometheusExemplarConfigurationTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realImportsRespectTracingSwitchAndScrapeRemainsAvailable(boolean enabled) {
        new ApplicationContextRunner().withUserConfiguration(TelemetryOnly.class)
                .withPropertyValues("kb.telemetry.tracing.enabled=" + enabled,
                        "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces")
                .withInitializer(context -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(PrometheusMeterRegistry.class);
                    var tracer = context.getBean(Tracer.class);
                    var observations = context.getBean(ObservationRegistry.class);
                    var timer = Timer.builder("kb.parse.task.duration").serviceLevelObjectives(Duration.ofSeconds(1)).register(registry);
                    Observation.createNotStarted("test.switch", observations).observe(() -> {
                        timer.record(Duration.ofMillis(100));
                        RestClient.builder().observationRegistry(observations).requestInterceptor((request, body, execution) -> {
                            assertThat(request.getHeaders().containsKey("traceparent")).isEqualTo(enabled);
                            assertThat(request.getHeaders()).doesNotContainKey("baggage");
                            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                        }).build().get().uri("http://internal.invalid/probe").retrieve().toBodilessEntity();
                    });
                    String scrape = registry.scrape("application/openmetrics-text;version=1.0.0;charset=utf-8");
                    assertThat(scrape).contains("kb_parse_task_duration_seconds_bucket");
                    if (enabled) {
                        assertThat(context).hasSingleBean(SpanContext.class);
                        assertThat(scrape).contains("trace_id", "span_id");
                    } else {
                        assertThat(context).doesNotHaveBean(SpanContext.class).doesNotHaveBean(SdkTracerProvider.class);
                        assertThat(tracer).isSameAs(Tracer.NOOP);
                        assertThat(scrape).doesNotContain("trace_id", "span_id", " # {");
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class,
            ObservationAutoConfiguration.class, org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
            OpenTelemetryAutoConfiguration.class, NoopTracerAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
            PrometheusExemplarsAutoConfiguration.class})
    static class TelemetryOnly {
    }
}
