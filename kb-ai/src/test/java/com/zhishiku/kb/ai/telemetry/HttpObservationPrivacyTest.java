package com.zhishiku.kb.ai.telemetry;

import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HttpObservationPrivacyTest {
    @Test
    void reactiveServerAndClientSpansExcludeRawUrisAndHeaders() {
        var exporter = InMemorySpanExporter.create();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                        org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                        OpenTelemetryAutoConfiguration.class, MicrometerTracingAutoConfiguration.class))
                .withBean(SpanExporter.class, () -> exporter)
                .withPropertyValues("management.tracing.sampling.probability=1", "management.tracing.baggage.enabled=false")
                .run(context -> {
                    var configuration = new HttpObservationConfiguration();
                    var registry = context.getBean(ObservationRegistry.class);
                    var client = WebClient.builder().observationRegistry(registry).observationConvention(configuration.clientRequestObservationConvention())
                            .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build())).build();
                    var handler = WebHttpHandlerBuilder.webHandler(exchange -> {
                        ServerRequestObservationContext.findCurrent(exchange.getAttributes()).orElseThrow().setPathPattern("/api/ai/chat");
                        return client.post().uri("http://app.invalid/api/search?query=secret-query").header("satoken", "secret-token")
                                .exchangeToMono(response -> Mono.empty()).then();
                    }).observationRegistry(registry).observationConvention(configuration.serverRequestObservationConvention("/actuator")).build();
                    StepVerifier.create(handler.handle(MockServerHttpRequest.post("/api/ai/chat?code=secret-code&state=secret-state")
                            .header("satoken", "secret-token").body("secret-question"), new MockServerHttpResponse())).verifyComplete();
                    context.getBean(SdkTracerProvider.class).forceFlush().join(1, TimeUnit.SECONDS);
                    assertThat(exporter.getFinishedSpanItems()).hasSize(2).allSatisfy(span -> {
                        assertThat(span.getAttributes().toString()).doesNotContain("secret-", "http.url", "http.request.header", "app.invalid", "?");
                        if (span.getAttributes().get(AttributeKey.stringKey("client.name")) == null) {
                            assertThat(span.getAttributes().get(AttributeKey.stringKey("http.route"))).isEqualTo("/api/ai/chat");
                        } else {
                            assertThat(span.getAttributes().get(AttributeKey.stringKey("client.name"))).isEqualTo("internal-app");
                        }
                    });
                });
    }
}
