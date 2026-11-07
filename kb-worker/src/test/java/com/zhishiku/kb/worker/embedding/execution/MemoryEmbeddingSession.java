package com.zhishiku.kb.worker.embedding.execution;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 直接驱动真实 BodyHandler 的内存传输，不创建监听端口。 */
public class MemoryEmbeddingSession implements WorkerEmbeddingTransport.Session {
    public final List<HttpRequest> requests = new ArrayList<>();
    public final AtomicInteger bodyRequests = new AtomicInteger();
    public final AtomicInteger cancellations = new AtomicInteger();
    public boolean shutdown;
    private final int status;
    private final byte[] bytes;
    private final HttpHeaders headers;

    public MemoryEmbeddingSession(int status, byte[] bytes, Map<String, List<String>> headers) {
        this.status = status;
        this.bytes = bytes;
        this.headers = HttpHeaders.of(headers, (name, value) -> true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<HttpResponse<InputStream>> send(HttpRequest request, HttpResponse.BodyHandler<InputStream> handler) {
        requests.add(request);
        var info = new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpHeaders headers() {
                return headers;
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
        var subscriber = handler.apply(info);
        var step = new AtomicInteger();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
                bodyRequests.incrementAndGet();
                int current = step.getAndIncrement();
                deliver(subscriber, current);
            }

            @Override
            public void cancel() {
                cancellations.incrementAndGet();
            }
        });
        return subscriber.getBody().thenApply(body -> {
            HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.body()).thenReturn(body);
            when(response.statusCode()).thenReturn(status);
            when(response.headers()).thenReturn(headers);
            return response;
        }).toCompletableFuture();
    }

    protected void deliver(HttpResponse.BodySubscriber<InputStream> subscriber, int current) {
        if (current == 0 && bytes.length > 0) {
            subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
        } else if (current <= 1) {
            subscriber.onComplete();
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return true;
    }
}
