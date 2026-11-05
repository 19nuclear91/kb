package com.zhishiku.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdkTransportTest extends TestSupport {
    @Test
    void knownSignatureVectorMatchesIndependentDotNetHmac() {
        // 固定向量由独立 .NET HMACSHA256 计算，不在断言时重跑被测算法。
        assertThat(DingTalkProtocol.signature(1700000000000L, SECRET))
                .isEqualTo("TYCnlNXzbMv2OwJt%2FopxujchHzepCj7mwksYxdawVEg%3D");
        assertThat(DingTalkProtocol.target(1700000000000L, settings).toString())
                .isEqualTo("https://oapi.dingtalk.com/robot/send?access_token=" + TOKEN
                        + "&timestamp=1700000000000&sign=TYCnlNXzbMv2OwJt%2FopxujchHzepCj7mwksYxdawVEg%3D");
    }

    @Test
    void productionClientHasNoAmbientIdentityProxyOrRedirects() {
        try (HttpClient client = JdkTransport.isolatedClient()) {
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2));
            assertThat(client.cookieHandler()).isEmpty();
            assertThat(client.authenticator()).isEmpty();
            assertThat(client.proxy().orElseThrow().select(URI.create(DingTalkProtocol.TARGET))).containsExactly(Proxy.NO_PROXY);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mockedJdkExchangeIsOnePostWithOnlyIsolatedHeaders() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(success().body());
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        when(client.awaitTermination(any(Duration.class))).thenReturn(true);
        behavior = new JdkTransport(() -> client);
        mvc.perform(post(webhook(1)).header("Cookie", "cookie-canary").header("traceparent", "trace-canary")
                .header("tracestate", "state-canary").header("baggage", "identity-canary").header("satoken", "session-canary"))
                .andExpect(status().isNoContent());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(1)).sendAsync(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().headers().map().keySet()).containsExactlyInAnyOrder("Accept", "Content-Type");
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().uri().getHost()).isEqualTo("oapi.dingtalk.com");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(8));
        verify(client).shutdownNow();
        verify(client).awaitTermination(Duration.ofSeconds(1));
    }

    @Test
    void arbitraryProductionTargetsAreRejectedBeforeClientCreation() {
        AtomicReference<Boolean> created = new AtomicReference<>(false);
        JdkTransport transport = new JdkTransport(() -> {
            created.set(true);
            throw new AssertionError();
        });
        for (String target : List.of("https://evil.invalid/robot/send", "http://oapi.dingtalk.com/robot/send",
                "https://oapi.dingtalk.com:443/robot/send", "https://user@oapi.dingtalk.com/robot/send")) {
            assertThatThrownBy(() -> transport.exchange(URI.create(target), new byte[0], new Deadline(nanos::get)))
                    .isInstanceOf(Rejection.class);
        }
        assertThat(created).hasValue(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingBodyIsIncludedInTotalDeadlineAndCancelledWithoutRetry() throws Exception {
        HttpClient client = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending = mock(CompletableFuture.class);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        when(pending.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            assertThat((Long) invocation.getArgument(0)).isEqualTo(Duration.ofSeconds(8).toNanos());
            nanos.addAndGet(Duration.ofSeconds(8).toNanos());
            throw new TimeoutException("sensitive-timeout-canary");
        });
        when(client.awaitTermination(any(Duration.class))).thenReturn(true);
        behavior = new JdkTransport(() -> client);
        mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
        verify(pending).cancel(true);
        verify(client).shutdownNow();
        verify(client).awaitTermination(Duration.ofSeconds(1));
        verify(client, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThat(admission.inflight()).isZero();
    }

    @Test
    void bodySubscriberCopiesOnlyBoundedBytesAndChecksDeadlineOnFinalByte() {
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        BoundedBody body = new BoundedBody(new Deadline(nanos::get));
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.allocate(BoundedJson.RESPONSE_BYTES)));
        body.onComplete();
        assertThat(body.getBody().toCompletableFuture().join()).hasSize(BoundedJson.RESPONSE_BYTES);
        BoundedBody overflow = new BoundedBody(new Deadline(nanos::get));
        overflow.onSubscribe(subscription);
        overflow.onNext(List.of(ByteBuffer.allocate(BoundedJson.RESPONSE_BYTES + 1)));
        assertThatThrownBy(() -> overflow.getBody().toCompletableFuture().join()).hasCauseInstanceOf(Rejection.class);
        verify(subscription).cancel();
        BoundedBody late = new BoundedBody(new Deadline(nanos::get));
        late.onSubscribe(mock(Flow.Subscription.class));
        late.onNext(List.of(ByteBuffer.wrap(success().body())));
        nanos.set(Duration.ofSeconds(8).toNanos());
        late.onComplete();
        assertThatThrownBy(() -> late.getBody().toCompletableFuture().join()).hasRootCauseMessage("timeout");
    }
}
