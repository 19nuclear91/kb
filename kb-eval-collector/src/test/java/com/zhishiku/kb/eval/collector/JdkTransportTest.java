package com.zhishiku.kb.eval.collector;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 只使用内存 Future，验证 JDK 传输交接与取消竞争，不发送请求。 */
class JdkTransportTest {
    @Test
    void closesAlreadyArrivedBodyWhenDeadlineExpiresBeforeHandoff() {
        AtomicLong clock = new AtomicLong();
        Deadline deadline = new Deadline(clock::get, 1);
        AtomicBoolean closed = new AtomicBoolean();
        CompletableFuture<HttpResponse<InputStream>> future = CompletableFuture.completedFuture(response(closed));
        clock.set(1_000_000);
        assertThatThrownBy(() -> JdkTransport.awaitResponse(future, deadline)).isInstanceOf(CollectionException.class)
                .hasMessageContaining("TIMEOUT");
        assertThat(closed).isTrue();
    }

    @Test
    void successfulHandoffLeavesClosingToOwner() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        Transport.Response response = JdkTransport.awaitResponse(CompletableFuture.completedFuture(response(closed)),
                new Deadline(() -> 0, 1000));
        assertThat(closed).isFalse();
        response.close();
        assertThat(closed).isTrue();
    }

    @Test
    void expiredPendingExchangeIsCancelled() {
        AtomicLong clock = new AtomicLong();
        Deadline deadline = new Deadline(clock::get, 1);
        CompletableFuture<HttpResponse<InputStream>> future = new CompletableFuture<>();
        clock.set(1_000_000);
        assertThatThrownBy(() -> JdkTransport.awaitResponse(future, deadline)).isInstanceOf(CollectionException.class);
        assertThat(future).isCancelled();
    }

    @Test
    void headerWaitTimeoutCancelsFuture() {
        CompletableFuture<HttpResponse<InputStream>> future = new CompletableFuture<>();
        assertThatThrownBy(() -> JdkTransport.awaitResponse(future, new Deadline(System::nanoTime, 10)))
                .isInstanceOf(CollectionException.class).hasMessageContaining("TIMEOUT");
        assertThat(future).isCancelled();
    }

    @Test
    void connectTimeoutIsAControlledRemoteFailure() {
        CompletableFuture<HttpResponse<InputStream>> future = CompletableFuture.failedFuture(new HttpTimeoutException(Fixtures.SECRET));
        assertThatThrownBy(() -> JdkTransport.awaitResponse(future, new Deadline(() -> 0, 1000)))
                .isInstanceOf(CollectionException.class).hasMessageContaining("TIMEOUT").hasMessageNotContaining(Fixtures.SECRET).hasNoCause();
    }

    private static HttpResponse<InputStream> response(AtomicBoolean closed) {
        InputStream stream = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpRequest request() {
                return HttpRequest.newBuilder(uri()).build();
            }

            @Override
            public Optional<HttpResponse<InputStream>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (name, value) -> true);
            }

            @Override
            public InputStream body() {
                return stream;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("https://example.invalid/api/search");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
