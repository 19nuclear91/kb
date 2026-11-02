package com.zhishiku.kb.app.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.sso.SsoHttp;
import com.zhishiku.kb.app.search.execution.SearchHttpTransport;
import com.zhishiku.kb.common.embedding.CallBudget;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SupplierPropagationTest {
    @Test
    @SuppressWarnings("unchecked")
    void ssoAndSearchHttpDoNotSendOrAdoptSupplierTraceHeaders() throws Exception {
        var requests = new ArrayList<HttpRequest>();
        var client = mock(HttpClient.class);
        when(client.executor()).thenReturn(Optional.empty());
        when(client.version()).thenReturn(HttpClient.Version.HTTP_1_1);
        when(client.send(any(), any())).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            return response();
        });
        when(client.sendAsync(any(), any())).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            return CompletableFuture.completedFuture(response());
        });
        var builder = mock(HttpClient.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(client);
        try (var tracing = new InMemoryTracing(); var http = mockStatic(HttpClient.class)) {
            http.when(HttpClient::newBuilder).thenReturn(builder);
            var root = tracing.tracer.nextSpan().name("test.vendor").start();
            try (var scope = tracing.tracer.withSpan(root);
                 var baggage = tracing.tracer.createBaggageInScope("requestId", "local-only");
                 var search = new SearchHttpTransport()) {
                var budget = mock(CallBudget.class);
                when(budget.remainingNanos()).thenReturn(Duration.ofSeconds(5).toNanos());
                when(budget.maxResponseBytes()).thenReturn(8192);
                search.post(URI.create("https://supplier.invalid/rerank"), "key", Map.of("query", "query"), budget, 8192, 5);
                var sso = new SsoHttp(new ObjectMapper());
                sso.call(sso.budget(), "POST", "https://open.feishu.cn/test", Map.of("Authorization", "Bearer key"), Map.of());
                assertThat(tracing.tracer.currentSpan().context().traceId()).isEqualTo(root.context().traceId());
                assertThat(tracing.tracer.getBaggage("requestId").get()).isEqualTo("local-only");
                assertThat(requests).hasSize(2);
                for (var request : requests) {
                    assertThat(request.headers().map().keySet()).noneMatch(name -> List.of("traceparent", "tracestate", "baggage", "satoken")
                            .contains(name.toLowerCase(java.util.Locale.ROOT)));
                    assertThat(request.headers().firstValue("Authorization")).contains("Bearer key");
                }
            } finally {
                root.end();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response() {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(
                "traceparent", List.of("00-11111111111111111111111111111111-2222222222222222-01"),
                "baggage", List.of("requestId=supplier")), (key, value) -> true));
        return response;
    }
}
