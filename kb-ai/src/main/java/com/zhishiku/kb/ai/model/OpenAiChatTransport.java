package com.zhishiku.kb.ai.model;

import io.netty.channel.ChannelOption;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 每次返回流仅允许消费一次，任何错误均不重试生成。 */
public class OpenAiChatTransport {
    private final WebClient client;
    private final OpenAiSseDecoder decoder;
    private final URI endpoint;
    private final String apiKey;
    private final int responseLimit;
    private final Duration idleTimeout;
    private final Duration totalTimeout;

    public OpenAiChatTransport(ChatModelProperties properties, int totalSeconds,
                               WebClient client, OpenAiSseDecoder decoder) {
        this.client = client.mutate().defaultHeaders(headers -> headers.clear())
                .defaultCookies(cookies -> cookies.clear()).filters(filters -> filters.clear())
                .filter((request, next) -> next.exchange(request)
                .map(OpenAiChatTransport::singleUseBody)).build();
        this.decoder = decoder;
        endpoint = properties.endpoint();
        apiKey = properties.getApiKey();
        responseLimit = properties.getMaxResponseBytes();
        idleTimeout = Duration.ofMillis(properties.getIdleTimeoutMs());
        totalTimeout = Duration.ofSeconds(totalSeconds);
    }

    public static WebClient isolatedClient(ChatModelProperties properties) {
        HttpClient http = HttpClient.create().followRedirect(false).disableRetry(true).wiretap(false)
                .compress(true).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs());
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    public Flux<ModelEvent> stream(PreparedChatRequest prepared) {
        var subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                return Flux.error(new ChatModelFailure(ChatModelFailure.Kind.ALREADY_SUBSCRIBED));
            }
            Scheduler clock = Schedulers.parallel();
            long deadline = clock.now(TimeUnit.MILLISECONDS) + totalTimeout.toMillis();
            byte[] bytes = prepared.encode();
            return client.post().uri(endpoint).headers(headers -> {
                        headers.clear();
                        headers.setBearerAuth(apiKey);
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                    }).cookies(cookies -> cookies.clear()).bodyValue(bytes)
                    .exchangeToFlux(this::readResponse)
                    // 每次活动只重置空闲期限，总期限固定；timeout 同时取消实际上游订阅。
                    .timeout(nextTimeout(clock, deadline), event -> nextTimeout(clock, deadline))
                    .filter(event -> event instanceof ModelEvent.Delta || event instanceof ModelEvent.FinalUsage);
        }).onErrorMap(error -> {
            if (error instanceof ChatModelFailure) {
                return error;
            }
            return new ChatModelFailure(error instanceof TimeoutException ? ChatModelFailure.Kind.TIMEOUT
                    : ChatModelFailure.Kind.CONNECT_FAILED);
        });
    }

    private Mono<Long> nextTimeout(Scheduler clock, long deadline) {
        long remaining = Math.max(0, deadline - clock.now(TimeUnit.MILLISECONDS));
        return Mono.delay(Duration.ofMillis(Math.min(idleTimeout.toMillis(), remaining)), clock);
    }

    private Flux<ModelEvent> readResponse(ClientResponse response) {
        if (response.statusCode().value() != 200) {
            abortBody(response);
            return Flux.error(new ChatModelFailure(ChatModelFailure.Kind.HTTP_REJECTED));
        }
        try {
            MediaType type = response.headers().contentType().orElse(null);
            if (type == null || !MediaType.TEXT_EVENT_STREAM.equalsTypeAndSubtype(type)
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                abortBody(response);
                return Flux.error(new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID));
            }
        } catch (RuntimeException error) {
            abortBody(response);
            return Flux.error(new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID));
        }
        var received = new AtomicLong();
        Flux<DataBuffer> body = response.bodyToFlux(DataBuffer.class).<DataBuffer>handle((buffer, sink) -> {
            if (received.addAndGet(buffer.readableByteCount()) > responseLimit) {
                DataBufferUtils.release(buffer);
                sink.error(new ChatModelFailure(ChatModelFailure.Kind.PAYLOAD_LIMIT));
            } else {
                sink.next(buffer);
            }
        }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
        return decoder.decode(body);
    }

    private static ClientResponse singleUseBody(ClientResponse response) {
        var consumed = new AtomicBoolean();
        return response.mutate().body(body -> Flux.defer(() -> consumed.compareAndSet(false, true)
                ? body : Flux.empty()).doOnDiscard(DataBuffer.class, DataBufferUtils::release)).build();
    }

    private static void abortBody(ClientResponse response) {
        response.bodyToFlux(DataBuffer.class).subscribe(new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                cancel();
            }

            @Override
            protected void hookOnNext(DataBuffer buffer) {
                DataBufferUtils.release(buffer);
            }

            @Override
            protected void hookOnError(Throwable error) {
                // 取消错误正文的清理不打印远程内容，也不创建第二个生成请求。
            }
        });
    }
}
