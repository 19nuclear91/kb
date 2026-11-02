package com.zhishiku.kb.app.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import com.zhishiku.kb.app.conversation.service.SettlementDeliveryClient;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalPropagationTest {
    private static final String INCOMING_TRACE = "11111111111111111111111111111111";
    private static final String INCOMING_PARENT = "2222222222222222";

    @Test
    void receivedW3cContextIsKeptByServerLogsAndSettlementDelivery() {
        var exporter = InMemorySpanExporter.create();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                        OpenTelemetryAutoConfiguration.class, MicrometerTracingAutoConfiguration.class))
                .withUserConfiguration(HttpObservationConfiguration.class)
                .withBean(SpanExporter.class, () -> exporter)
                .withPropertyValues("kb.telemetry.tracing.enabled=true", "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces")
                .withInitializer(context -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(ObservationRegistry.class);
                    var sent = new AtomicReference<HttpHeaders>();
                    var builder = RestClient.builder().observationRegistry(registry)
                            .observationConvention(context.getBean(HttpObservationConfiguration.ClientConvention.class))
                            .requestInterceptor((request, body, execution) -> {
                                sent.set(request.getHeaders());
                                return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                            });
                    var properties = new ConversationProperties();
                    properties.setSecret("0123456789abcdef0123456789abcdef");
                    var endpoint = new Endpoint(new SettlementDeliveryClient(builder, properties, new ObjectMapper(), "http://ai.invalid"));
                    var logger = (Logger) LoggerFactory.getLogger(Endpoint.class);
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
                        var mvc = MockMvcBuilders.standaloneSetup(endpoint).addFilters(new ServerHttpObservationFilter(registry,
                                context.getBean(HttpObservationConfiguration.ServerConvention.class)), new RequestIdFilter()).build();
                        mvc.perform(get("/internal/telemetry").header("traceparent", "00-" + INCOMING_TRACE + "-" + INCOMING_PARENT + "-01")
                                .header("X-Request-Id", "local-request").header("baggage", "requestId=untrusted")).andExpect(status().isOk());
                        context.getBean(SdkTracerProvider.class).forceFlush().join(1, TimeUnit.SECONDS);
                        assertThat(exporter.getFinishedSpanItems()).hasSize(2).allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(INCOMING_TRACE));
                        assertThat(exporter.getFinishedSpanItems()).filteredOn(span -> span.getKind() == SpanKind.SERVER)
                                .singleElement().satisfies(span -> assertThat(span.getParentSpanId()).isEqualTo(INCOMING_PARENT));
                        assertThat(logs.list).hasSize(2).allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                                .containsEntry("traceId", INCOMING_TRACE).containsEntry("requestId", "local-request"));
                        assertThat(sent.get().getFirst("traceparent")).contains(INCOMING_TRACE);
                        assertThat(sent.get()).doesNotContainKey("baggage");
                        assertThat(MDC.get("traceId")).isNull();
                        assertThat(MDC.get("spanId")).isNull();
                        assertThat(MDC.get("requestId")).isNull();
                        assertThat(registry.getCurrentObservation()).isNull();
                    } finally {
                        logger.detachAppender(logs);
                        logs.stop();
                    }
                });
    }

    @Test
    void settlementDeliveryCarriesInternalTraceWithoutBaggage() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                OpenTelemetryAutoConfiguration.class))
                .withPropertyValues("kb.telemetry.tracing.enabled=true", "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces")
                .withInitializer(context -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null))
                .run(context -> {
                    var tracer = context.getBean(Tracer.class);
                    var registry = ObservationRegistry.create();
                    registry.observationConfig().observationHandler(new PropagatingSenderTracingObservationHandler<>(
                            tracer, context.getBean(Propagator.class)));
                    var sent = new AtomicReference<HttpHeaders>();
                    var builder = RestClient.builder().observationRegistry(registry).requestInterceptor((request, body, execution) -> {
                        sent.set(request.getHeaders());
                        return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                    });
                    var properties = new ConversationProperties();
                    properties.setSecret("0123456789abcdef0123456789abcdef");
                    var client = new SettlementDeliveryClient(builder, properties, new ObjectMapper(), "http://ai.invalid");
                    var root = tracer.nextSpan().name("test.settle").start();
                    try (var scope = tracer.withSpan(root); var baggage = tracer.createBaggageInScope("requestId", "local-only")) {
                        client.deliver(new SettlementRow());
                    } finally {
                        root.end();
                    }
                    assertThat(sent.get().getFirst("traceparent")).contains(root.context().traceId());
                    assertThat(sent.get().getFirst("X-Service-Signature")).isNotBlank();
                    assertThat(sent.get()).doesNotContainKeys("tracestate", "baggage");
                });
    }

    @RestController
    static class Endpoint {
        private final SettlementDeliveryClient delivery;

        Endpoint(SettlementDeliveryClient delivery) {
            this.delivery = delivery;
        }

        @GetMapping("/internal/telemetry")
        String request() {
            LoggerFactory.getLogger(Endpoint.class).info("接收内部调用");
            delivery.deliver(new SettlementRow());
            LoggerFactory.getLogger(Endpoint.class).info("内部调用结束");
            return "ok";
        }
    }
}
