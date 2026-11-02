package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.telemetry.TelemetryEnvironmentPostProcessor;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSupplierPropagationTest {
    @Test
    void observedInputClientCannotPropagateOrReplaceLocalContext() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                OpenTelemetryAutoConfiguration.class))
                .withPropertyValues("kb.telemetry.tracing.enabled=true",
                        "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces")
                .withInitializer(context -> new TelemetryEnvironmentPostProcessor()
                        .postProcessEnvironment(context.getEnvironment(), null))
                .run(context -> {
                    var tracer = context.getBean(Tracer.class);
                    var registry = ObservationRegistry.create();
                    registry.observationConfig().observationHandler(new PropagatingSenderTracingObservationHandler<>(
                            tracer, context.getBean(Propagator.class)));
                    var captured = new AtomicReference<HttpHeaders>();
                    var client = WebClient.builder().observationRegistry(registry)
                            .defaultHeader("satoken", "user-secret").exchangeFunction(request -> {
                                captured.set(request.headers());
                                return Mono.just(ClientResponse.create(HttpStatus.OK)
                                        .header("Content-Type", "text/event-stream")
                                        .header("traceparent", "00-11111111111111111111111111111111-2222222222222222-01")
                                        .header("tracestate", "supplier=value")
                                        .header("baggage", "requestId=supplier")
                                        .body(Flux.empty()).build());
                            }).build();
                    var transport = new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client,
                            OpenAiChatTransportTest.decoder());
                    var root = tracer.nextSpan().name("test.model").start();
                    try (var scope = tracer.withSpan(root);
                         var baggage = tracer.createBaggageInScope("requestId", "local-only")) {
                        StepVerifier.create(transport.stream(OpenAiChatTransportTest.request()))
                                .expectNext(new ModelEvent.Delta("answer"), new ModelEvent.FinalUsage(10, 2))
                                .verifyComplete();
                        assertThat(tracer.currentSpan().context().traceId()).isEqualTo(root.context().traceId());
                        assertThat(tracer.getBaggage("requestId").get()).isEqualTo("local-only");
                        assertThat(captured.get()).doesNotContainKeys("traceparent", "tracestate", "baggage", "satoken");
                    } finally {
                        root.end();
                    }
                });
    }
}
