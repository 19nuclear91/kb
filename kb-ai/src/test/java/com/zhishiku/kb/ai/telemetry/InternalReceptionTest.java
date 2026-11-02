package com.zhishiku.kb.ai.telemetry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立 JVM 隔离先前 SDK 初始化，保留本地 baggage 的实际恢复断言。 */
class InternalReceptionTest {
    private static final String INCOMING_TRACE = "11111111111111111111111111111111";
    private static final String INCOMING_PARENT = "2222222222222222";

    @Test
    void receivedW3cContextSurvivesReactiveServerLogsAndInternalRetrieval() {
        var exporter = InMemorySpanExporter.create();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                            org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                            OpenTelemetryAutoConfiguration.class, MicrometerTracingAutoConfiguration.class, ReactorAutoConfiguration.class))
                    .withUserConfiguration(ReactiveRequestContext.class, HttpObservationConfiguration.class)
                    .withBean(SpanExporter.class, () -> exporter)
                    .withPropertyValues("kb.telemetry.tracing.enabled=true", "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces",
                            "spring.reactor.context-propagation=auto")
                    .withInitializer(context -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var registry = context.getBean(ObservationRegistry.class);
                        var sent = new AtomicReference<HttpHeaders>();
                        var builder = WebClient.builder().observationRegistry(registry)
                                .observationConvention(context.getBean(HttpObservationConfiguration.ClientConvention.class)).exchangeFunction(request -> {
                                    sent.set(request.headers());
                                    return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                            .body("{\"code\":200,\"data\":[]}").build());
                                });
                        var client = new RetrievalClient(builder, new AiProperties("http://app.invalid", 5, 1000, 60));
                        var scheduler = Schedulers.newSingle("internal-receiver");
                        var logger = (Logger) LoggerFactory.getLogger(InternalReceptionTest.class);
                        var logs = new ListAppender<ILoggingEvent>() {
                            @Override
                            protected void append(ILoggingEvent event) {
                                event.prepareForDeferredProcessing();
                                super.append(event);
                            }
                        };
                        logs.start();
                        logger.addAppender(logs);
                        try {
                            var handler = WebHttpHandlerBuilder.webHandler(exchange -> Mono.defer(() -> {
                                logger.info("接收内部调用");
                                return client.retrieve("question", "token").doOnSuccess(ignored -> logger.info("内部调用结束")).then();
                            }).subscribeOn(scheduler)).observationRegistry(registry)
                                    .observationConvention(context.getBean(HttpObservationConfiguration.ServerConvention.class))
                                    .filter(context.getBean(ReactiveRequestContext.RequestIdWebFilter.class)).build();
                            StepVerifier.create(handler.handle(MockServerHttpRequest.post("/api/ai/chat")
                                    .header("traceparent", "00-" + INCOMING_TRACE + "-" + INCOMING_PARENT + "-01")
                                    .header("X-Request-Id", "local-request").header("baggage", "requestId=untrusted").build(),
                                    new MockServerHttpResponse())).verifyComplete();
                            // 同一调度线程的屏障保证上游 doFinally 已执行，不能只等待下游完成信号。
                            StepVerifier.create(Mono.fromCallable(() -> Boolean.TRUE).subscribeOn(scheduler)).expectNext(true).verifyComplete();
                            context.getBean(SdkTracerProvider.class).forceFlush().join(1, TimeUnit.SECONDS);
                            assertThat(logs.list).hasSize(2).allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                                    .containsEntry("traceId", INCOMING_TRACE).containsEntry("requestId", "local-request"));
                            assertThat(sent.get().getFirst("traceparent")).contains(INCOMING_TRACE);
                            assertThat(sent.get()).doesNotContainKey("baggage");
                            assertThat(exporter.getFinishedSpanItems()).hasSize(2).allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(INCOMING_TRACE));
                            assertThat(exporter.getFinishedSpanItems()).filteredOn(span -> span.getKind() == SpanKind.SERVER)
                                    .singleElement().satisfies(span -> assertThat(span.getParentSpanId()).isEqualTo(INCOMING_PARENT));
                            StepVerifier.create(Mono.fromCallable(() -> MDC.getCopyOfContextMap() == null ? Map.<String, String>of() : MDC.getCopyOfContextMap())
                                    .subscribeOn(scheduler)).assertNext(map -> assertThat(map).doesNotContainKeys("traceId", "spanId", "requestId")).verifyComplete();
                        } finally {
                            scheduler.dispose();
                            logger.detachAppender(logs);
                            logs.stop();
                        }
                    });
        } finally {
            Hooks.disableAutomaticContextPropagation();
        }
    }
}
