package com.zhishiku.kb.ai.model;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenAiChatTransportTest {
    static PreparedChatRequest request() {
        var request = mock(PreparedChatRequest.class);
        when(request.encode()).thenReturn(new byte[]{'{', '}'});
        return request;
    }

    static OpenAiSseDecoder decoder() {
        var decoder = mock(OpenAiSseDecoder.class);
        when(decoder.decode(any())).thenAnswer(call -> {
            Flux<DataBuffer> buffers = call.getArgument(0);
            return buffers.doOnNext(DataBufferUtils::release).thenMany(Flux.just(
                    new ModelEvent.Delta("answer"), new ModelEvent.FinalUsage(10, 2), ModelEvent.Signal.END));
        });
        return decoder;
    }

    static ClientResponse response(int status, String contentType, Flux<DataBuffer> body) {
        return ClientResponse.create(HttpStatusCode.valueOf(status))
                .header(HttpHeaders.CONTENT_TYPE, contentType).body(body).build();
    }

    @Test
    void sendsExactlyOneIsolatedRequestAndPreservesVersionPrefix() {
        var captured = new AtomicReference<ClientRequest>();
        var calls = new AtomicInteger();
        var client = WebClient.builder().defaultHeader("satoken", "user-secret")
                .defaultHeader("X-Internal-Signature", "internal-secret").defaultCookie("session", "cookie-secret")
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    captured.set(request);
                    return Mono.just(response(200, "text/event-stream;charset=UTF-8", Flux.empty()));
                }).build();
        var transport = new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, decoder());
        var prepared = request();
        Flux<ModelEvent> stream = transport.stream(prepared);
        assertThat(calls.get()).isZero();
        StepVerifier.create(stream).expectNext(new ModelEvent.Delta("answer"), new ModelEvent.FinalUsage(10, 2))
                .verifyComplete();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(captured.get().url().toString()).isEqualTo("https://model.invalid/v1/chat/completions");
        assertThat(captured.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer secret-canary");
        assertThat(captured.get().headers().containsKey("satoken")).isFalse();
        assertThat(captured.get().headers().containsKey("X-Internal-Signature")).isFalse();
        assertThat(captured.get().cookies()).isEmpty();
        StepVerifier.create(stream).expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                .isEqualTo(ChatModelFailure.Kind.ALREADY_SUBSCRIBED)).verify();
        assertThat(calls.get()).isEqualTo(1);
        verify(prepared).encode();
    }

    @Test
    void rejectsStatusesAndMediaTypesWithoutConsumingOrResubscribingErrorBodies() {
        for (int status : new int[]{302, 401, 429, 500, 503}) {
            var calls = new AtomicInteger();
            var subscriptions = new AtomicInteger();
            var requests = new AtomicInteger();
            var cancelled = new AtomicInteger();
            Flux<DataBuffer> body = Flux.<DataBuffer>never().doOnSubscribe(s -> subscriptions.incrementAndGet())
                    .doOnRequest(n -> requests.incrementAndGet()).doOnCancel(cancelled::incrementAndGet);
            var client = WebClient.builder().exchangeFunction(request -> {
                calls.incrementAndGet();
                return Mono.just(response(status, "text/plain", body));
            }).build();
            StepVerifier.create(new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, decoder())
                            .stream(request())).expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                            .isEqualTo(ChatModelFailure.Kind.HTTP_REJECTED)).verify();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(subscriptions.get()).isEqualTo(1);
            assertThat(requests.get()).isZero();
            assertThat(cancelled.get()).isEqualTo(1);
        }
        for (String type : new String[]{"application/json", "text/event-stream;charset=ISO-8859-1", "not a media type"}) {
            var client = WebClient.builder().exchangeFunction(request -> Mono.just(response(200, type, Flux.never()))).build();
            StepVerifier.create(new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, decoder())
                            .stream(request())).expectError(ChatModelFailure.class).verify();
        }
    }

    @Test
    void cancelsUpstreamAndDoesNotResubscribeWhenConsumptionIsCancelled() {
        TestPublisher<DataBuffer> source = TestPublisher.create();
        var client = WebClient.builder().exchangeFunction(request -> Mono.just(response(200, "text/event-stream", source.flux())))
                .build();
        var transport = new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, decoder());
        StepVerifier.create(transport.stream(request())).expectSubscription()
                .thenAwait(Duration.ofMillis(10)).thenCancel().verify();
        source.assertCancelled();
    }

    @Test
    void encodingAndTransportErrorsNeverLeakDetailsOrRetry() {
        var calls = new AtomicInteger();
        var client = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.error(new IllegalStateException("secret-document https://secret.invalid?token=secret"));
        }).build();
        var transport = new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, decoder());
        StepVerifier.create(transport.stream(request())).expectErrorSatisfies(error -> {
            assertThat(error).hasMessage("CONNECT_FAILED").hasNoCause();
            assertThat(error.toString()).doesNotContain("secret");
        }).verify();
        assertThat(calls.get()).isEqualTo(1);
        var prepared = request();
        when(prepared.encode()).thenThrow(new ChatModelFailure(ChatModelFailure.Kind.PAYLOAD_LIMIT));
        StepVerifier.create(transport.stream(prepared)).expectError(ChatModelFailure.class).verify();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rejectsSecondSubscriptionWhileFirstIsStillActive() {
        var calls = new AtomicInteger();
        TestPublisher<DataBuffer> source = TestPublisher.create();
        var client = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(response(200, "text/event-stream", source.flux()));
        }).build();
        var stream = new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, decoder()).stream(request());
        StepVerifier.create(stream).expectSubscription().thenAwait(Duration.ofMillis(10)).then(() -> {
            StepVerifier.create(stream).expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                    .isEqualTo(ChatModelFailure.Kind.ALREADY_SUBSCRIBED)).verify();
            assertThat(calls.get()).isEqualTo(1);
        }).thenCancel().verify();
        source.assertCancelled();
    }

    @Test
    void rejectsWildcardMediaTypesBeforeDecodingAndCancelsTheirBodiesOnce() {
        for (String type : new String[]{"text/*", "*/*", "text/*;charset=UTF-8", "text/*+event-stream"}) {
            var calls = new AtomicInteger();
            var subscriptions = new AtomicInteger();
            var requests = new AtomicInteger();
            var cancelled = new AtomicInteger();
            Flux<DataBuffer> body = Flux.<DataBuffer>never().doOnSubscribe(s -> subscriptions.incrementAndGet())
                    .doOnRequest(n -> requests.incrementAndGet()).doOnCancel(cancelled::incrementAndGet);
            var client = WebClient.builder().exchangeFunction(request -> {
                calls.incrementAndGet();
                return Mono.just(response(200, type, body));
            }).build();
            var decoder = decoder();
            StepVerifier.create(new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, decoder)
                            .stream(request())).expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                            .isEqualTo(ChatModelFailure.Kind.PROTOCOL_INVALID)).verify(Duration.ofSeconds(2));
            verifyNoInteractions(decoder);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(subscriptions.get()).isEqualTo(1);
            assertThat(requests.get()).isZero();
            assertThat(cancelled.get()).isEqualTo(1);
        }
    }

    @Test
    void acceptsConcreteSseMediaTypesWithParametersAndMixedCase() {
        for (String type : new String[]{"text/event-stream", "Text/Event-Stream; Charset=UTF-8", "text/event-stream;v=1"}) {
            var client = WebClient.builder().exchangeFunction(request -> Mono.just(response(200, type, Flux.empty())))
                    .build();
            StepVerifier.create(new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, decoder())
                            .stream(request())).expectNext(new ModelEvent.Delta("answer"), new ModelEvent.FinalUsage(10, 2))
                    .verifyComplete();
        }
    }
}
