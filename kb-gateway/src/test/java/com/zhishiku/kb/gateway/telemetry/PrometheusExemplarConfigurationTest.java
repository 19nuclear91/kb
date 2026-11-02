package com.zhishiku.kb.gateway.telemetry;

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
import org.springframework.cloud.gateway.filter.headers.observation.ObservedRequestHttpHeadersFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

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
                    var observations = context.getBean(ObservationRegistry.class);
                    var timer = Timer.builder("http.server.requests").serviceLevelObjectives(Duration.ofSeconds(1)).register(registry);
                    Observation.createNotStarted("test.switch", observations).observe(() -> timer.record(Duration.ofMillis(100)));
                    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/probe"));
                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                            Route.async().id("kb-app").uri("http://internal.invalid").predicate(value -> true).build());
                    var headers = new ObservedRequestHttpHeadersFilter(observations).filter(exchange.getRequest().getHeaders(), exchange);
                    assertThat(headers.containsKey("traceparent")).isEqualTo(enabled);
                    assertThat(headers).doesNotContainKey("baggage");
                    String scrape = registry.scrape("application/openmetrics-text;version=1.0.0;charset=utf-8");
                    assertThat(scrape).contains("http_server_requests_seconds_bucket");
                    if (enabled) {
                        assertThat(context).hasSingleBean(SpanContext.class);
                        assertThat(scrape).contains("trace_id", "span_id");
                    } else {
                        assertThat(context).doesNotHaveBean(SpanContext.class).doesNotHaveBean(SdkTracerProvider.class);
                        assertThat(context.getBean(Tracer.class)).isSameAs(Tracer.NOOP);
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
