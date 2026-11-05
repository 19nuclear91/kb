package com.zhishiku.kb.eval.collector;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 仅供独立 CLI 使用：在 JDK 客户端初始化前固定进程级重试策略。 */
final class JdkTransport implements Transport {
    private final HttpClient client;

    JdkTransport(Inputs.Config config) {
        client = createClient(config);
    }

    static HttpClient createClient(Inputs.Config config) {
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "false");
        System.setProperty("jdk.httpclient.redirects.retrylimit", "1");
        System.setProperty("jdk.httpclient.HttpClient.log", "");
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException exception) {
                    }
                }).build();
    }

    @Override
    public Response send(Request request, Deadline deadline) throws IOException, InterruptedException {
        deadline.check();
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(Duration.ofNanos(deadline.remainingNanos()));
        request.headers().forEach(builder::header);
        builder.method(request.method(), request.body().length == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        return awaitResponse(future, deadline);
    }

    static Response awaitResponse(CompletableFuture<HttpResponse<InputStream>> future, Deadline deadline)
            throws InterruptedException {
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicReference<HttpResponse<InputStream>> arrived = new AtomicReference<>();
        future.whenComplete((response, failure) -> {
            if (response != null) {
                arrived.set(response);
                if (abandoned.get()) {
                    close(arrived.getAndSet(null));
                }
            }
        });
        boolean handedOver = false;
        try {
            HttpResponse<InputStream> response = future.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            deadline.check();
            handedOver = true;
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CollectionException(CollectionException.Code.TIMEOUT, "response", 0, "request");
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                throw new CollectionException(CollectionException.Code.TIMEOUT, "response", 0, "request");
            }
            throw new CollectionException(CollectionException.Code.TRANSPORT, "response", 0, "request");
        } catch (RuntimeException e) {
            future.cancel(true);
            throw e;
        } finally {
            if (!handedOver) {
                abandoned.set(true);
                future.cancel(true);
                close(arrived.getAndSet(null));
            }
        }
    }

    private static void close(HttpResponse<InputStream> response) {
        if (response != null) {
            try {
                response.body().close();
            } catch (IOException e) {
                // 未交付的响应已经失败，仅释放资源，不回显底层异常。
            }
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
