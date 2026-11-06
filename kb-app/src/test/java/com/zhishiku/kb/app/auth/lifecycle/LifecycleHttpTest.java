package com.zhishiku.kb.app.auth.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifecycleHttpTest {
    @Test
    void outboundContainsOnlyFixedHeadersAndRejectsRedirectOrMalformedBody() {
        var captured = new AtomicReference<HttpRequest>();
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        var http = new LifecycleHttp(new ObjectMapper(), DingTalkLifecycleSettingsTest.configured(false), request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(response);
        });
        http.post("https://api.dingtalk.com/v1.0/oauth2/accessToken", Map.of());
        assertThat(captured.get().headers().map().keySet()).containsExactlyInAnyOrder("Accept", "Content-Type");
        assertThat(captured.get().timeout()).contains(java.time.Duration.ofSeconds(2));
        when(response.statusCode()).thenReturn(302);
        assertThatThrownBy(() -> http.post("https://api.dingtalk.com/path", Map.of()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(503));
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("broken-secret".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> http.post("https://api.dingtalk.com/path", Map.of())).hasMessageNotContaining("broken-secret");
        assertThatThrownBy(() -> http.post("https://evil.example/path", Map.of())).isInstanceOf(ApiException.class);
    }

    @Test
    void oversizedStreamingBodyIsCancelledBeforeRetainingIt() {
        var body = new LifecycleHttp.BoundedBody();
        var subscription = mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.allocate(LifecycleHttp.MAX_BYTES + 1)));
        verify(subscription).cancel();
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
}
