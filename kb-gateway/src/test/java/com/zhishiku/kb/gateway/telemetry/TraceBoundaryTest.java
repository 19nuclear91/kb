package com.zhishiku.kb.gateway.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.json.SaJsonTemplateForJackson;
import cn.dev33.satoken.reactor.spring.SaTokenContextForSpringReactor;
import cn.dev33.satoken.reactor.spring.SaTokenContextRegister;
import cn.dev33.satoken.strategy.SaStrategy;
import com.zhishiku.kb.gateway.config.SaTokenGatewayConfig;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.handler.TracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.filter.headers.observation.ObservedRequestHttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.observation.ObservedResponseHttpHeadersFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceBoundaryTest {
    private static final String CLIENT_TRACE = "11111111111111111111111111111111";

    @Test
    void stripsBeforeObservationAndForwardsOnlyGatewayContextWithoutWarnings() {
        var exporter = InMemorySpanExporter.create();
        context(exporter).run(context -> {
            assertThat(context).hasNotFailed();
            var registry = registry(context.getBean(Tracer.class), context.getBean(Propagator.class));
            var outgoing = new AtomicReference<HttpHeaders>();
            WebHandler downstream = exchange -> Mono.deferContextual(reactorContext -> {
                assertThat(exchange.getRequest().getHeaders()).doesNotContainKeys("traceparent", "tracestate", "baggage");
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REACTOR_CONTEXT_ATTR, reactorContext);
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                        Route.async().id("kb-app").uri("http://app:8081").predicate(ignored -> true).build());
                outgoing.set(new ObservedRequestHttpHeadersFilter(registry, new HttpObservationConfiguration.ClientConvention())
                        .filter(exchange.getRequest().getHeaders(), exchange));
                exchange.getResponse().setStatusCode(HttpStatus.OK);
                new ObservedResponseHttpHeadersFilter().filter(new HttpHeaders(), exchange);
                exchange.getResponse().getHeaders().set("traceparent", "downstream-value");
                return exchange.getResponse().setComplete();
            });
            var handler = new TraceBoundaryConfiguration().traceBoundary()
                    .apply(WebHttpHandlerBuilder.webHandler(downstream).observationRegistry(registry)
                            .observationConvention(new HttpObservationConfiguration.ServerConvention()).build());
            var response = new MockServerHttpResponse();
            var logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            var logs = new ListAppender<ILoggingEvent>();
            logs.start();
            logger.addAppender(logs);
            try {
                StepVerifier.create(handler.handle(request(), response)).verifyComplete();
                context.getBean(SdkTracerProvider.class).forceFlush().join(1, TimeUnit.SECONDS);
                assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
            } finally {
                logger.detachAppender(logs);
                logs.stop();
            }
            assertThat(exporter.getFinishedSpanItems()).hasSize(2);
            var root = exporter.getFinishedSpanItems().stream().filter(span -> !span.getParentSpanContext().isValid()).findFirst().orElseThrow();
            assertThat(root.getTraceId()).isNotEqualTo(CLIENT_TRACE);
            assertThat(exporter.getFinishedSpanItems()).allMatch(span -> span.getTraceId().equals(root.getTraceId()));
            assertThat(root.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("http.route"))).isEqualTo("kb-app");
            assertThat(exporter.getFinishedSpanItems()).allSatisfy(span -> assertThat(span.getAttributes().toString())
                    .doesNotContain("secret-", "http.url", "http.request.header", "42", "?"));
            assertThat(outgoing.get().getFirst("traceparent")).contains(root.getTraceId());
            assertThat(outgoing.get()).doesNotContainKeys("tracestate", "baggage");
            assertThat(response.getHeaders()).doesNotContainKeys("traceparent", "tracestate", "baggage");
        });
    }

    @Test
    void authenticationRejectionRemainsInsideRootObservation() {
        var exporter = InMemorySpanExporter.create();
        var originalContext = SaManager.getSaTokenContext();
        var originalJson = SaManager.getSaJsonTemplate();
        var originalRouteMatcher = SaStrategy.instance.routeMatcher;
        var originalRequest = SaStrategy.instance.createSaRequest;
        var originalResponse = SaStrategy.instance.createSaResponse;
        var originalStorage = SaStrategy.instance.createSaStorage;
        new SaTokenContextRegister();
        SaManager.setSaTokenContext(new SaTokenContextForSpringReactor());
        SaManager.setSaJsonTemplate(new SaJsonTemplateForJackson());
        try {
            context(exporter).run(context -> {
            var registry = registry(context.getBean(Tracer.class), context.getBean(Propagator.class));
            var auth = new SaTokenGatewayConfig().saReactorFilter();
            auth.setAuth(ignored -> {
                throw new NotLoginException(NotLoginException.NOT_TOKEN_MESSAGE, NotLoginException.NOT_TOKEN, null);
            });
            var activeTrace = new AtomicReference<String>();
            var handler = WebHttpHandlerBuilder.webHandler(exchange -> Mono.error(new AssertionError("认证拒绝必须终止路由")))
                    .observationRegistry(registry)
                    .filter((exchange, chain) -> Mono.deferContextual(reactorContext -> {
                        Observation observation = reactorContext.get("micrometer.observation");
                        TracingObservationHandler.TracingContext tracing = observation.getContext()
                                .get(TracingObservationHandler.TracingContext.class);
                        activeTrace.set(tracing.getSpan().context().traceId());
                        return chain.filter(exchange);
                    }), auth).build();
            var response = new MockServerHttpResponse();
            StepVerifier.create(new TraceBoundaryConfiguration().traceBoundary().apply(handler).handle(request(), response)).verifyComplete();
            context.getBean(SdkTracerProvider.class).forceFlush().join(1, TimeUnit.SECONDS);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exporter.getFinishedSpanItems()).singleElement().satisfies(span -> {
                assertThat(span.getTraceId()).isEqualTo(activeTrace.get()).isNotEqualTo(CLIENT_TRACE);
                assertThat(span.getParentSpanContext().isValid()).isFalse();
            });
            assertThat(response.getHeaders()).doesNotContainKeys("traceparent", "tracestate", "baggage");
                assertThat(response.getBodyAsString().block()).doesNotContain(activeTrace.get(), CLIENT_TRACE);
            });
        } finally {
            SaManager.setSaTokenContext(originalContext);
            SaManager.setSaJsonTemplate(originalJson);
            SaStrategy.instance.routeMatcher = originalRouteMatcher;
            SaStrategy.instance.createSaRequest = originalRequest;
            SaStrategy.instance.createSaResponse = originalResponse;
            SaStrategy.instance.createSaStorage = originalStorage;
        }
    }

    private static MockServerHttpRequest request() {
        return MockServerHttpRequest.get("/api/documents/42?code=secret-code&state=secret-state")
                .header("traceparent", "00-" + CLIENT_TRACE + "-2222222222222222-01")
                .header("tracestate", "client=secret").header("baggage", "requestId=secret").build();
    }

    private static ObservationRegistry registry(Tracer tracer, Propagator propagator) {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator)));
        return registry;
    }

    private static ApplicationContextRunner context(InMemorySpanExporter exporter) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                OpenTelemetryAutoConfiguration.class))
                .withBean(SpanExporter.class, () -> exporter)
                .withPropertyValues("kb.telemetry.tracing.enabled=true", "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces")
                .withInitializer(context -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null));
    }
}
