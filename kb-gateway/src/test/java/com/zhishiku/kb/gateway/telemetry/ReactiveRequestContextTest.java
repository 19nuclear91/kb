package com.zhishiku.kb.gateway.telemetry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactiveRequestContextTest {
    @Test
    void reactiveSignalsKeepLocalCorrelationAndNeverPropagateBaggage() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                        OpenTelemetryAutoConfiguration.class, MicrometerTracingAutoConfiguration.class, ReactorAutoConfiguration.class))
                .withUserConfiguration(ReactiveRequestContext.class)
                .withPropertyValues("kb.telemetry.tracing.enabled=true", "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces",
                        "spring.reactor.context-propagation=auto")
                .withInitializer(context -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null))
                .run(context -> {
                    var registry = context.getBean(ObservationRegistry.class);
                    var first = Schedulers.newSingle("gateway-first");
                    var second = Schedulers.newSingle("gateway-second");
                    var logger = (Logger) LoggerFactory.getLogger(ReactiveRequestContextTest.class);
                    var logs = new ListAppender<ILoggingEvent>() {
                        @Override
                        protected void append(ILoggingEvent event) {
                            event.prepareForDeferredProcessing();
                            super.append(event);
                        }
                    };
                    logs.start();
                    logger.addAppender(logs);
                    List<String> traces = new ArrayList<>();
                    var client = WebClient.builder().observationRegistry(registry).exchangeFunction(request -> {
                        assertThat(request.headers()).doesNotContainKey("baggage");
                        traces.add(request.headers().getFirst("traceparent"));
                        return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                    }).build();
                    try {
                        var handler = WebHttpHandlerBuilder.webHandler(exchange -> exchange.getResponse().writeWith(Flux.just("a", "b")
                                        .publishOn(first).doOnNext(value -> logger.info("转发开始"))
                                        .publishOn(second).concatMap(value -> client.get().uri("http://ai.invalid/probe")
                                                .exchangeToMono(response -> Mono.just(value)))
                                        .doOnNext(value -> logger.info("转发完成"))
                                        .map(value -> exchange.getResponse().bufferFactory().wrap(value.getBytes(StandardCharsets.UTF_8)))))
                                .observationRegistry(registry).filter(context.getBean(ReactiveRequestContext.RequestIdWebFilter.class)).build();
                        StepVerifier.create(handler.handle(MockServerHttpRequest.get("/api/ai/chat").header("X-Request-Id", "request-1").build(),
                                new MockServerHttpResponse())).verifyComplete();
                        assertThat(logs.list).hasSize(4);
                        String trace = logs.list.getFirst().getMDCPropertyMap().get("traceId");
                        assertThat(trace).hasSize(32);
                        assertThat(logs.list).allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                                .containsEntry("traceId", trace).containsEntry("requestId", "request-1"));
                        assertThat(traces).hasSize(2).allSatisfy(value -> assertThat(value).contains(trace));
                        for (var scheduler : List.of(first, second)) {
                            StepVerifier.create(Mono.fromRunnable(() -> {
                                assertThat(MDC.get("traceId")).isNull();
                                assertThat(MDC.get("requestId")).isNull();
                            }).subscribeOn(scheduler)).verifyComplete();
                        }
                    } finally {
                        first.dispose();
                        second.dispose();
                        logger.detachAppender(logs);
                        logs.stop();
                    }
                });
        Hooks.disableAutomaticContextPropagation();
    }
}
