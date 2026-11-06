package com.zhishiku.kb.app.auth.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 独立客户端，无用户/追踪头，无重试，响应体和整次等待均有界。 */
@Component
public class LifecycleHttp {
    static final int MAX_BYTES = 64 * 1024;
    private final ObjectMapper json;
    private final DingTalkLifecycleSettings settings;
    private final Transport transport;
    private final ThreadPoolExecutor executor;
    private final HttpClient client;

    @FunctionalInterface
    interface Transport {
        CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request);
    }

    @Autowired
    public LifecycleHttp(ObjectMapper json, DingTalkLifecycleSettings settings) {
        this.json = json;
        this.settings = settings;
        this.executor = executor("lifecycle-http", 2, 32);
        this.client = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofMillis(settings.timeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.transport = request -> client.sendAsync(request, ignored -> new BoundedBody());
    }

    LifecycleHttp(ObjectMapper json, DingTalkLifecycleSettings settings, Transport transport) {
        this.json = json;
        this.settings = settings;
        this.transport = transport;
        this.executor = null;
        this.client = null;
    }

    public JsonNode post(String url, Object body) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !Set.of("api.dingtalk.com", "oapi.dingtalk.com").contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw LifecycleFailures.unavailable();
            }
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(settings.timeoutMillis()))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            pending = transport.send(request);
            var response = pending.get(settings.timeoutMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200 || response.body() == null || response.body().length > MAX_BYTES) {
                throw LifecycleFailures.unavailable();
            }
            JsonNode result = json.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw LifecycleFailures.unavailable();
            }
            return result;
        } catch (Exception failure) {
            if (pending != null) {
                pending.cancel(true);
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw LifecycleFailures.unavailable();
        }
    }

    static ThreadPoolExecutor executor(String name, int threads, int queue) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.shutdownNow();
            executor.shutdownNow();
        }
    }

    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > MAX_BYTES - buffer.size()) {
                    subscription.cancel();
                    result.completeExceptionally(LifecycleFailures.unavailable());
                    return;
                }
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(LifecycleFailures.unavailable());
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }
    }
}
