package com.zhishiku.kb.worker.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.worker.telemetry.InMemoryTracing;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingSupplierIsolationTest {
    @Test
    void legacyClientIgnoresInjectedHeadersAndKeepsTimeout() throws Exception {
        byte[] response = new ObjectMapper().writeValueAsBytes(Map.of("data", List.of(Map.of("index", 0,
                "embedding", Collections.nCopies(1024, 1)))));
        var sent = new ArrayList<MockClientHttpRequest>();
        var tracing = new InMemoryTracing();
        var root = tracing.tracer.nextSpan().name("test.embedding").start();
        try (var scope = tracing.tracer.withSpan(root);
             var baggage = tracing.tracer.createBaggageInScope("requestId", "local-only");
             var factories = mockConstruction(JdkClientHttpRequestFactory.class, (factory, context) -> {
            when(factory.createRequest(any(), any())).thenAnswer(invocation -> {
                var request = new MockClientHttpRequest(invocation.getArgument(1), invocation.getArgument(0));
                var reply = new MockClientHttpResponse(response, HttpStatus.OK);
                reply.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                reply.getHeaders().set("traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
                reply.getHeaders().set("baggage", "requestId=supplier");
                request.setResponse(reply);
                sent.add(request);
                return request;
            });
        })) {
            var builder = RestClient.builder().observationRegistry(tracing.registry).defaultHeaders(headers -> {
                for (String header : List.of("traceparent", "tracestate", "baggage", "satoken")) {
                    headers.set(header, "injected-secret");
                }
            });
            var legacy = new OpenAiCompatibleEmbeddingClient(
                    new EmbeddingProperties("remote", "https://supplier.invalid", "key", "model", 1024, 16, 7), builder);
            assertThat(legacy.embed(List.of("document"))).hasSize(1);
            assertThat(sent).hasSize(1);
            for (var request : sent) {
                assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer key");
                assertThat(request.getHeaders()).doesNotContainKeys("traceparent", "tracestate", "baggage", "satoken");
                assertThat(request.getBodyAsString()).contains("document", "model");
            }
            for (var factory : factories.constructed()) {
                verify(factory).setReadTimeout(Duration.ofSeconds(7));
            }
            assertThat(tracing.tracer.currentSpan().context().traceId()).isEqualTo(root.context().traceId());
            assertThat(tracing.tracer.getBaggage("requestId").get()).isEqualTo("local-only");
        } finally {
            root.end();
            tracing.close();
        }
    }
}
