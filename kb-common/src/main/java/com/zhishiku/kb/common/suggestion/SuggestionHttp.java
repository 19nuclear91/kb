package com.zhishiku.kb.common.suggestion;

import com.zhishiku.kb.common.conversation.RequestSignature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** 受限的 app/AI 内部传输，任何对外异常均不携带用户材料。 */
public class SuggestionHttp {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;
    private final String caller;
    private final String secret;

    public SuggestionHttp(String base, String caller, String secret) {
        this.base = base;
        this.caller = caller;
        this.secret = secret;
    }

    public void validate() {
        SuggestionProtocol.requireSecret(secret);
        URI uri;
        try {
            uri = URI.create(base == null ? "" : base);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("内部整理地址无效");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalStateException("内部整理地址无效");
        }
    }

    public byte[] post(String operation, byte[] body, String token, Duration timeout, int responseLimit) {
        validate();
        String path = SuggestionProtocol.ROOT + operation;
        var uri = URI.create(base).resolve(path);
        if (body.length > SuggestionProtocol.MATERIAL_LIMIT) {
            throw new IllegalStateException("内部整理地址或载荷无效");
        }
        var headers = RequestSignature.sign(secret, caller, "POST", path, body, token, Clock.systemUTC());
        var request = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                .header("X-Service-Caller", caller).header("X-Service-Nonce", headers.nonce())
                .header("X-Service-Signature", headers.signature())
                .header("X-Service-Timestamp", Long.toString(headers.timestamp()));
        if (token != null) {
            request.header("satoken", token);
        }
        var pending = http.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                info -> new LimitedBody(responseLimit));
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("内部请求未确认");
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("内部请求中止");
        } catch (Exception failed) {
            throw new IllegalStateException("内部请求未确认");
        } finally {
            pending.cancel(true);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private long received;
        private Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                received += buffer.remaining();
            }
            if (received > limit) {
                subscription.cancel();
                delegate.onError(new IllegalStateException("内部响应过大"));
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable failure) {
            delegate.onError(new IllegalStateException("内部响应未确认"));
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
