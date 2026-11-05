package com.zhishiku.notifier;

import java.io.IOException;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

final class JdkTransport implements Transport {
    private final Supplier<HttpClient> clients;

    JdkTransport() {
        this(JdkTransport::isolatedClient);
    }

    JdkTransport(Supplier<HttpClient> clients) {
        this.clients = clients;
    }

    static HttpClient isolatedClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException exception) {
                        // 不记录含机器人凭据的 URI。
                    }
                }).build();
    }

    @Override
    public Response exchange(URI uri, byte[] body, Deadline deadline) {
        if (!"https".equals(uri.getScheme()) || !"oapi.dingtalk.com".equals(uri.getHost())
                || !"/robot/send".equals(uri.getPath()) || uri.getPort() != -1 || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new Rejection(503, Rejection.Reason.internal);
        }
        HttpClient client = clients.get();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        boolean interrupted = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(deadline.remainingNanos()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            // get 的期限覆盖响应头及完整正文。仅 JDK 内部网络异步；本请求始终同步等待并回收客户端。
            pending = client.sendAsync(request, info -> new BoundedBody(deadline));
            HttpResponse<byte[]> response = pending.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            deadline.remainingNanos();
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            interrupted = true;
            throw new Rejection(503, Rejection.Reason.cancelled);
        } catch (TimeoutException exception) {
            throw new Rejection(503, Rejection.Reason.timeout);
        } catch (CancellationException exception) {
            throw new Rejection(503, Rejection.Reason.cancelled);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Rejection rejection) {
                throw rejection;
            }
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw new Rejection(503, Rejection.Reason.timeout);
            }
            throw new Rejection(503, Rejection.Reason.network);
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
            client.shutdownNow();
            // cancel 完成不等于网络任务退出。即使调用方取消，也必须等终止后才能释放并发槽。
            boolean terminated = false;
            while (!terminated) {
                try {
                    terminated = client.awaitTermination(Duration.ofSeconds(1));
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
