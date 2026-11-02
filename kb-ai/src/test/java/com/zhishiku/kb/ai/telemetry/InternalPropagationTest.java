package com.zhishiku.kb.ai.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.chat.QuotaPrincipalClient;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.conversation.ConversationAppClient;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.ai.observation.AiObservationClient;
import com.zhishiku.kb.ai.observation.AiObservationProperties;
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
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalPropagationTest {
    @Test
    void allInternalClientsCarryTheSameW3cTraceWithoutBaggage() {
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
                    Map<String, HttpHeaders> sent = new LinkedHashMap<>();
                    var builder = WebClient.builder().observationRegistry(registry).exchangeFunction(request -> {
                        String path = request.url().getPath();
                        sent.put(path, request.headers());
                        String body = switch (path) {
                            case "/api/search" -> "{\"code\":200,\"data\":[]}";
                            case "/api/auth/quota-context" -> "{\"code\":200,\"data\":{\"userId\":1,\"departmentId\":2}}";
                            default -> "{\"code\":200,\"data\":{}}";
                        };
                        return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .body(body).build());
                    });
                    var ai = mock(AiProperties.class);
                    when(ai.appBaseUrl()).thenReturn("http://app.invalid");
                    when(ai.retrieveTopK()).thenReturn(8);
                    when(ai.upstreamTimeout()).thenReturn(60);
                    var conversations = new ConversationProperties();
                    conversations.setSecret("0123456789abcdef0123456789abcdef");
                    var observations = new AiObservationProperties(context.getEnvironment());
                    observations.setEnabled(true);
                    observations.setSecret("0123456789abcdef0123456789abcdef");
                    var root = tracer.nextSpan().name("test.internal").start();
                    try (var scope = tracer.withSpan(root); var baggage = tracer.createBaggageInScope("requestId", "local-only")) {
                        new RetrievalClient(builder.clone(), ai).retrieve("question", "token").block();
                        new QuotaPrincipalClient(builder.clone(), ai).resolve("token").block();
                        new ConversationAppClient(builder.clone(), ai, conversations, new ObjectMapper())
                                .call("check", Map.of(), "token", false).block();
                        new AiObservationClient(builder.clone(), ai, observations, new ObjectMapper().findAndRegisterModules())
                                .rejectedMulti(UUID.randomUUID()).block();
                    } finally {
                        root.end();
                    }
                    assertThat(sent).containsOnlyKeys("/api/search", "/api/auth/quota-context",
                            "/internal/conversations/check", "/internal/ai-observability/events");
                    sent.values().forEach(headers -> {
                        assertThat(headers.getFirst("traceparent")).contains(root.context().traceId());
                        assertThat(headers).doesNotContainKeys("baggage", "tracestate");
                    });
                });
    }
}
