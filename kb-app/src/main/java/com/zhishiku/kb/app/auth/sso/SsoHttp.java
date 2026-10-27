package com.zhishiku.kb.app.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 固定官方主机、有界响应和整轮时间预算；无自动重试。
 */
@Component
public class SsoHttp {
    public static final int MAX_BYTES = 512 * 1024;
    private static final Set<String> HOSTS = Set.of("api.dingtalk.com", "oapi.dingtalk.com",
            "qyapi.weixin.qq.com", "open.feishu.cn");
    private static final ExecutorService CALLS = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
        Thread thread = new Thread(runnable, "sso-http");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final Transport transport;
    private final ObjectMapper json;
    private final LongSupplier nanos;

    @Autowired
    public SsoHttp(ObjectMapper json) {
        this(new SpringTransport(), json, System::nanoTime);
    }

    SsoHttp(Transport transport, ObjectMapper json, LongSupplier nanos) {
        this.transport = transport;
        this.json = json;
        this.nanos = nanos;
    }

    public Budget budget() {
        return new Budget(nanos.getAsLong() + TimeUnit.SECONDS.toNanos(30));
    }

    public JsonNode call(Budget budget, String method, String url, Map<String, String> headers, Object body) {
        Future<Response> work = null;
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                throw SsoException.unavailable();
            long remaining = budget.deadline - nanos.getAsLong();
            if (remaining <= 0) throw SsoException.unavailable();
            Duration timeout = Duration.ofNanos(Math.min(TimeUnit.SECONDS.toNanos(10), remaining));
            byte[] data = body == null ? null : json.writeValueAsBytes(body);
            work = CALLS.submit(() -> transport.send(method, uri, headers, data, timeout));
            Response response = work.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (nanos.getAsLong() >= budget.deadline || response.status() != 200 || response.body().length > MAX_BYTES)
                throw SsoException.unavailable();
            JsonNode result = json.readTree(response.body());
            if (result == null || !result.isObject()) throw SsoException.unavailable();
            return result;
        } catch (Exception failure) {
            if (work != null) work.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw SsoException.unavailable();
        }
    }

    /**
     * 一轮核验的单调时钟截止值。
     *
     * @param deadline 纳秒截止值
     */
    public record Budget(long deadline) {
    }

    /**
     * 注入传输结果，不保留请求或平台错误文本。
     *
     * @param status HTTP 状态
     * @param body   有界字节响应
     */
    public record Response(int status, byte[] body) {
    }

    @FunctionalInterface
    public interface Transport {
        Response send(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) throws Exception;
    }

    private static final class SpringTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(timeout);
            var request = RestClient.builder().requestFactory(factory).build().method(HttpMethod.valueOf(method))
                    .uri(uri).headers(h -> {
                        h.set("Accept", "application/json");
                        headers.forEach(h::set);
                    });
            if (body != null) request.header("Content-Type", "application/json").body(body);
            return request.exchange((outgoing, incoming) -> {
                if (incoming.getStatusCode().value() != 200)
                    return new Response(incoming.getStatusCode().value(), new byte[0]);
                try (InputStream stream = incoming.getBody()) {
                    byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                    if (bytes.length > MAX_BYTES) throw SsoException.unavailable();
                    return new Response(200, bytes);
                }
            });
        }
    }
}
