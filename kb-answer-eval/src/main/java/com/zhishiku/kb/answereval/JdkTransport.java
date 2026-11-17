package com.zhishiku.kb.answereval;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** 仅在线 CLI 创建；不使用系统代理、Cookie、重定向或自动连接重试。 */
public final class JdkTransport implements Transport {
    private final HttpClient client;

    public JdkTransport(int connectTimeoutMs) {
        Checks.require(connectTimeoutMs > 0 && connectTimeoutMs <= 60_000);
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "false");
        System.setProperty("jdk.httpclient.redirects.retrylimit", "1");
        System.setProperty("jdk.httpclient.HttpClient.log", "");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                    }
                }).build();
    }

    @Override
    public DataModel.InvocationOrigin invocationOrigin() {
        return DataModel.InvocationOrigin.HTTP_UNVERIFIED;
    }

    @Override
    public Response send(Request request, Deadline deadline) throws IOException, InterruptedException {
        deadline.check();
        var builder = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofNanos(deadline.remainingNanos()));
        request.headers().forEach(builder::header);
        builder.method(request.method(), request.body().length == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new Response(response.statusCode(), response.headers(), response.body());
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
