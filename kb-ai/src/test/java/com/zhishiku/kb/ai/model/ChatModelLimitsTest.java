package com.zhishiku.kb.ai.model;

import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatModelLimitsTest {
    private static OpenAiSseDecoder activityDecoder() {
        var decoder = mock(OpenAiSseDecoder.class);
        when(decoder.decode(any())).thenAnswer(call -> {
            Flux<DataBuffer> body = call.getArgument(0);
            return body.<ModelEvent>handle((buffer, sink) -> {
                try {
                    String value = buffer.toString(StandardCharsets.UTF_8);
                    if (value.equals("tick")) {
                        sink.next(ModelEvent.Signal.ACTIVITY);
                    } else if (value.equals("done")) {
                        sink.next(ModelEvent.Signal.END);
                    }
                } finally {
                    DataBufferUtils.release(buffer);
                }
            }).takeUntil(event -> event == ModelEvent.Signal.END);
        });
        return decoder;
    }

    private static OpenAiChatTransport transport(Flux<DataBuffer> body, ChatModelProperties properties,
                                                int seconds, OpenAiSseDecoder decoder) {
        var client = WebClient.builder().exchangeFunction(request -> Mono.just(
                OpenAiChatTransportTest.response(200, "text/event-stream", body))).build();
        return new OpenAiChatTransport(properties, seconds, client, decoder);
    }

    @Test
    void absoluteDeadlineSurvivesContinuousActivity() {
        var cancelled = new AtomicBoolean();
        StepVerifier.withVirtualTime(() -> {
            var properties = ChatModelPropertiesTest.configured();
            properties.setIdleTimeoutMs(2000);
            Flux<DataBuffer> body = Flux.interval(Duration.ofSeconds(1))
                    .map(t -> OpenAiSseDecoderTest.buffer("tick")).doOnCancel(() -> cancelled.set(true));
            return transport(body, properties, 3, activityDecoder()).stream(OpenAiChatTransportTest.request());
        }).expectSubscription().thenAwait(Duration.ofSeconds(4))
                .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                        .isEqualTo(ChatModelFailure.Kind.TIMEOUT)).verify();
        assertThat(cancelled.get()).isTrue();
    }

    @Test
    void commentsAndStopWaitingDoNotBypassIdleDeadline() {
        for (boolean initialActivity : new boolean[]{false, true}) {
            StepVerifier.withVirtualTime(() -> {
                var properties = ChatModelPropertiesTest.configured();
                properties.setIdleTimeoutMs(1000);
                Flux<DataBuffer> comments = Flux.interval(Duration.ofMillis(100))
                        .map(t -> OpenAiSseDecoderTest.buffer(": comment"));
                Flux<DataBuffer> body = initialActivity
                        ? Flux.just(OpenAiSseDecoderTest.buffer("tick")).concatWith(comments) : comments;
                return transport(body, properties, 60, activityDecoder()).stream(OpenAiChatTransportTest.request());
            }).expectSubscription().thenAwait(Duration.ofSeconds(2))
                    .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                            .isEqualTo(ChatModelFailure.Kind.TIMEOUT)).verify();
        }
    }

    @Test
    void boundsWaitingForHeadersAndCancelsDeadlineAfterNormalCompletion() {
        StepVerifier.withVirtualTime(() -> {
            var properties = ChatModelPropertiesTest.configured();
            properties.setIdleTimeoutMs(1000);
            var client = WebClient.builder().exchangeFunction(request -> Mono.never()).build();
            return new OpenAiChatTransport(properties, 60, client, activityDecoder())
                    .stream(OpenAiChatTransportTest.request());
        }).expectSubscription().thenAwait(Duration.ofSeconds(2)).expectError(ChatModelFailure.class).verify();
        StepVerifier.withVirtualTime(() -> transport(Flux.just(OpenAiSseDecoderTest.buffer("done")),
                        ChatModelPropertiesTest.configured(), 1, activityDecoder()).stream(OpenAiChatTransportTest.request()))
                .expectSubscription().thenAwait(Duration.ofSeconds(10)).verifyComplete();
    }

    @Test
    void releasesPooledBuffersOnSuccessLimitFailureAndDiscard() {
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        List<NettyDataBuffer> success = List.of(factory.allocateBuffer(4), factory.allocateBuffer(4));
        success.forEach(buffer -> buffer.write(new byte[4]));
        StepVerifier.create(transport(Flux.fromIterable(success), ChatModelPropertiesTest.configured(), 60,
                        OpenAiChatTransportTest.decoder()).stream(OpenAiChatTransportTest.request()))
                .expectNextCount(2).verifyComplete();
        success.forEach(buffer -> assertThat(buffer.getNativeBuffer().refCnt()).isZero());

        List<NettyDataBuffer> failure = List.of(factory.allocateBuffer(4), factory.allocateBuffer(4), factory.allocateBuffer(4));
        failure.forEach(buffer -> buffer.write(new byte[4]));
        var properties = ChatModelPropertiesTest.configured();
        properties.setMaxEventBytes(5);
        properties.setMaxResponseBytes(5);
        StepVerifier.create(transport(Flux.fromIterable(failure), properties, 60, OpenAiChatTransportTest.decoder())
                        .stream(OpenAiChatTransportTest.request()))
                .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                        .isEqualTo(ChatModelFailure.Kind.PAYLOAD_LIMIT)).verify();
        failure.forEach(buffer -> assertThat(buffer.getNativeBuffer().refCnt()).isZero());
    }

    @Test
    void decoderLimitsAnswerCodepointsAndCumulativeSmallFrames() {
        var properties = ChatModelPropertiesTest.configured();
        properties.setMaxAnswerCodepoints(1);
        StepVerifier.create(new OpenAiSseDecoder(properties).decode(Flux.just(
                        OpenAiSseDecoderTest.buffer(OpenAiSseDecoderTest.text("😀😀")))))
                .expectError(ChatModelFailure.class).verify();
        properties.setMaxResponseBytes(10);
        StepVerifier.create(new OpenAiSseDecoder(properties).decode(Flux.range(0, 100)
                        .map(i -> OpenAiSseDecoderTest.buffer(": x\n\n"))))
                .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                        .isEqualTo(ChatModelFailure.Kind.PAYLOAD_LIMIT)).verify();
    }

    @Test
    void decoderReleasesPartialAndInvalidPooledBuffers() {
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        var partial = factory.allocateBuffer(16);
        partial.write("data: {".getBytes(StandardCharsets.UTF_8));
        TestPublisher<DataBuffer> source = TestPublisher.create();
        StepVerifier.create(new OpenAiSseDecoder(ChatModelPropertiesTest.configured()).decode(source.flux()))
                .then(() -> source.next(partial)).thenCancel().verify();
        source.assertCancelled();
        assertThat(partial.getNativeBuffer().refCnt()).isZero();

        var invalid = factory.allocateBuffer(16);
        invalid.write("data: {}\n\n".getBytes(StandardCharsets.UTF_8));
        StepVerifier.create(new OpenAiSseDecoder(ChatModelPropertiesTest.configured()).decode(Flux.just(invalid)))
                .expectError(ChatModelFailure.class).verify();
        assertThat(invalid.getNativeBuffer().refCnt()).isZero();
    }
}
