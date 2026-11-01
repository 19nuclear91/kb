package com.zhishiku.kb.ai.model;

import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiSseDecoderTest {
    static String event(String json) {
        return "data: " + json + "\n\n";
    }

    static String text(String value) {
        return event("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + value + "\"}}]}");
    }

    static String stop() {
        return event("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
    }

    static String usage(String prompt, String completion) {
        return event("{\"choices\":[],\"usage\":{\"prompt_tokens\":" + prompt
                + ",\"completion_tokens\":" + completion + "}}");
    }

    static DataBuffer buffer(String value) {
        return DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    static Flux<ModelEvent> decode(String value) {
        return new OpenAiSseDecoder(ChatModelPropertiesTest.configured()).decode(Flux.just(buffer(value)))
                .filter(e -> e != ModelEvent.Signal.ACTIVITY && e != ModelEvent.Signal.END);
    }

    @Test
    void decodesEveryByteSplitAndEveryLineEnding() {
        for (String ending : List.of("\n", "\r\n", "\r")) {
            String wire = (": keep-alive\n\n" + event("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}")
                    + text("中文😀") + stop() + usage("10", "4") + event("[DONE]")).replace("\n", ending);
            List<DataBuffer> bytes = new ArrayList<>();
            for (byte b : wire.getBytes(StandardCharsets.UTF_8)) {
                bytes.add(DefaultDataBufferFactory.sharedInstance.wrap(new byte[]{b}));
            }
            StepVerifier.create(new OpenAiSseDecoder(ChatModelPropertiesTest.configured()).decode(Flux.fromIterable(bytes))
                            .filter(e -> e instanceof ModelEvent.Delta || e instanceof ModelEvent.FinalUsage))
                    .expectNext(new ModelEvent.Delta("中文😀"), new ModelEvent.FinalUsage(10, 4)).verifyComplete();
        }
    }

    @Test
    void deliversTextBeforeEndAndCancelsAnOpenSourceAtDone() {
        TestPublisher<DataBuffer> source = TestPublisher.create();
        StepVerifier.create(new OpenAiSseDecoder(ChatModelPropertiesTest.configured()).decode(source.flux()))
                .then(() -> source.next(buffer(text("首段"))))
                .expectNext(new ModelEvent.Delta("首段"), ModelEvent.Signal.ACTIVITY)
                .then(() -> source.next(buffer(stop() + event("[DONE]"))))
                .expectNext(ModelEvent.Signal.ACTIVITY, ModelEvent.Signal.END).verifyComplete();
        source.assertCancelled();
    }

    @Test
    void supportsMultilineDataAndBodyAndStopInTheSameEvent() {
        String multiline = "data: {\"choices\":[{\"index\":0,\n"
                + "data: \"delta\":{\"content\":\"答\"},\"finish_reason\":\"stop\"}]}\n\n";
        StepVerifier.create(decode(multiline + event("[DONE]"))).expectNext(new ModelEvent.Delta("答")).verifyComplete();
    }

    @Test
    void rejectsBrokenJsonAndUnsupportedShapes() {
        for (String json : List.of("{}", "[]", "{\"choices\":[],\"choices\":[]}", "{} {}",
                "{\"error\":\"secret\"}", "{\"choices\":[{},{}]}",
                "{\"choices\":[{\"index\":1,\"delta\":{}}]}",
                "{\"choices\":[{\"index\":0.0,\"delta\":{}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"system\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":[]}}]}")) {
            StepVerifier.create(decode(event(json))).expectError(ChatModelFailure.class).verify();
        }
        for (String field : List.of("tool_calls", "function_call", "refusal", "reasoning_content", "reasoning", "thinking", "audio")) {
            StepVerifier.create(decode(event("{\"choices\":[{\"index\":0,\"delta\":{\"" + field + "\":\"secret\"}}]}")))
                    .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                            .isEqualTo(ChatModelFailure.Kind.UNSUPPORTED_OUTPUT)).verify();
        }
    }

    @Test
    void requiresBothStopAndDoneAndNeverAcceptsTruncationAsSuccess() {
        for (String wire : List.of("", text("答"), text("答") + stop(), text("答") + event("[DONE]"),
                stop() + event("[DONE]"), text("答") + stop() + text("迟到"),
                text("答") + "data: [DONE]", text("答") + stop() + "data: [DONE]\n")) {
            StepVerifier.create(decode(wire)).thenConsumeWhile(e -> e instanceof ModelEvent.Delta)
                    .expectError(ChatModelFailure.class).verify();
        }
        for (String reason : List.of("length", "content_filter", "tool_calls", "unknown")) {
            StepVerifier.create(decode(text("前缀") + event("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                            + reason + "\"}]}")))
                    .expectNext(new ModelEvent.Delta("前缀")).expectError(ChatModelFailure.class).verify();
        }
    }

    @Test
    void confirmsOnlyLastTailUsageAfterDoneAndPreservesKnownZero() {
        StepVerifier.create(decode(text("答") + stop() + usage("10", "2") + usage("10", "4") + event("[DONE]")))
                .expectNext(new ModelEvent.Delta("答"), new ModelEvent.FinalUsage(10, 4)).verifyComplete();
        StepVerifier.create(decode(text("答") + stop() + usage("0", "0") + event("[DONE]")))
                .expectNext(new ModelEvent.Delta("答"), new ModelEvent.FinalUsage(0, 0)).verifyComplete();
        StepVerifier.create(decode(usage("9", "2") + text("答") + stop() + event("[DONE]")))
                .expectNext(new ModelEvent.Delta("答")).verifyComplete();
        StepVerifier.create(decode(text("答") + stop() + usage("9", "2")))
                .expectNext(new ModelEvent.Delta("答")).expectError(ChatModelFailure.class).verify();
    }

    @Test
    void nullUsageIsUnknownButMalformedIntegersAreRejected() {
        StepVerifier.create(decode(text("答") + event("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}")
                        + event("[DONE]"))).expectNext(new ModelEvent.Delta("答")).verifyComplete();
        for (String value : List.of("-1", "1.2", "9223372036854775808", "null", "\"1\"")) {
            StepVerifier.create(decode(usage(value, "1"))).expectError(ChatModelFailure.class).verify();
        }
        StepVerifier.create(decode(event("{\"choices\":[],\"usage\":{\"prompt_tokens\":1}}")))
                .expectError(ChatModelFailure.class).verify();
    }

    @Test
    void cancellationDoesNotPublishPendingUsageAndLongValuesRemainExact() {
        TestPublisher<DataBuffer> source = TestPublisher.create();
        StepVerifier.create(new OpenAiSseDecoder(ChatModelPropertiesTest.configured()).decode(source.flux()))
                .then(() -> source.next(buffer(text("答") + stop() + usage("10", "2"))))
                .expectNext(new ModelEvent.Delta("答"), ModelEvent.Signal.ACTIVITY,
                        ModelEvent.Signal.ACTIVITY, ModelEvent.Signal.ACTIVITY)
                .thenCancel().verify();
        source.assertCancelled();
        StepVerifier.create(decode(text("答") + stop() + usage("9223372036854775807", "2") + event("[DONE]")))
                .expectNext(new ModelEvent.Delta("答"), new ModelEvent.FinalUsage(Long.MAX_VALUE, 2)).verifyComplete();
    }

    @Test
    void rejectsInvalidUnicodeDepthAndSizeBeforeCompletion() {
        for (String value : List.of("\\uD800", "\\uDC00", "\\u0000")) {
            StepVerifier.create(decode(text(value))).expectError(ChatModelFailure.class).verify();
        }
        var decoder = new OpenAiSseDecoder(ChatModelPropertiesTest.configured());
        StepVerifier.create(decoder.decode(Flux.just(DefaultDataBufferFactory.sharedInstance
                        .wrap(new byte[]{(byte) 0xc3, 0x28, '\n'}))))
                .expectError(ChatModelFailure.class).verify();
        StepVerifier.create(decode(event("{\"x\":" + "[".repeat(40) + "0" + "]".repeat(40) + ",\"choices\":[]}")))
                .expectError(ChatModelFailure.class).verify();
        var properties = ChatModelPropertiesTest.configured();
        properties.setMaxEventBytes(10);
        StepVerifier.create(new OpenAiSseDecoder(properties).decode(Flux.just(buffer("data: " + "x".repeat(100)))))
                .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                        .isEqualTo(ChatModelFailure.Kind.PAYLOAD_LIMIT)).verify();
    }

    @Test
    void countsEveryRawCrlfByteAtTheEventLimitAcrossAllPacketSplits() {
        String frame = ": a\r\n".repeat(50) + "data: {\"choices\":[{\"index\":0,\r\n"
                + "data: \"delta\":{\"content\":\"答😀\"}}]}\r\n\r\n";
        int frameBytes = frame.getBytes(StandardCharsets.UTF_8).length;
        byte[] wire = (frame + frame + (stop() + event("[DONE]")).replace("\n", "\r\n"))
                .getBytes(StandardCharsets.UTF_8);
        for (int limit : new int[]{frameBytes, frameBytes - 1, frameBytes - 25}) {
            for (int split = 0; split <= wire.length; split++) {
                var properties = ChatModelPropertiesTest.configured();
                properties.setMaxEventBytes(limit);
                Flux<ModelEvent> result = new OpenAiSseDecoder(properties).decode(Flux.just(
                        DefaultDataBufferFactory.sharedInstance.wrap(Arrays.copyOfRange(wire, 0, split)),
                        DefaultDataBufferFactory.sharedInstance.wrap(Arrays.copyOfRange(wire, split, wire.length))))
                        .filter(e -> e instanceof ModelEvent.Delta || e instanceof ModelEvent.FinalUsage);
                if (limit == frameBytes) {
                    StepVerifier.create(result).expectNext(new ModelEvent.Delta("答😀"), new ModelEvent.Delta("答😀"))
                            .verifyComplete();
                } else {
                    StepVerifier.create(result).thenConsumeWhile(e -> e instanceof ModelEvent.Delta)
                            .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                                    .isEqualTo(ChatModelFailure.Kind.PAYLOAD_LIMIT)).verify();
                }
            }
        }
    }

    @Test
    void cancelsImmediatelyWhenLfOfSplitCrlfIsTheFirstExcessByte() {
        var properties = ChatModelPropertiesTest.configured();
        properties.setMaxEventBytes(4);
        TestPublisher<DataBuffer> source = TestPublisher.create();
        StepVerifier.create(new OpenAiSseDecoder(properties).decode(source.flux()))
                .then(() -> source.next(buffer(": a\r")))
                .then(() -> source.next(buffer("\n")))
                .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                        .isEqualTo(ChatModelFailure.Kind.PAYLOAD_LIMIT)).verify(Duration.ofSeconds(2));
        source.assertCancelled();
    }

    @Test
    void singleCrTerminatorsDeliverAndCompleteImmediatelyWithOneByteReserved() {
        String frame = text("answer".repeat(10)).replace("\n", "\r");
        var properties = ChatModelPropertiesTest.configured();
        properties.setMaxEventBytes(frame.getBytes(StandardCharsets.UTF_8).length + 1);
        TestPublisher<DataBuffer> source = TestPublisher.create();
        StepVerifier.create(new OpenAiSseDecoder(properties).decode(source.flux()))
                .then(() -> source.next(buffer(frame)))
                .expectNext(new ModelEvent.Delta("answer".repeat(10)), ModelEvent.Signal.ACTIVITY)
                .then(() -> source.next(buffer((stop() + event("[DONE]")).replace("\n", "\r"))))
                .expectNext(ModelEvent.Signal.ACTIVITY, ModelEvent.Signal.END)
                .expectComplete().verify(Duration.ofSeconds(2));
        source.assertCancelled();
    }

    @Test
    void terminalBudgetPrecedesUsageForEveryLineEndingAndPacketSplit() {
        String prefix = text("answer") + stop() + usage("10", "2");
        for (String ending : List.of("\n", "\r\n", "\r")) {
            String terminal = (": a\n".repeat(20) + event("[DONE]")).replace("\n", ending);
            int required = terminal.getBytes(StandardCharsets.UTF_8).length + ("\r".equals(ending) ? 1 : 0);
            byte[] wire = (prefix + terminal).getBytes(StandardCharsets.UTF_8);
            for (int limit : new int[]{required - 1, required}) {
                for (int split = -1; split <= wire.length; split++) {
                    Flux<DataBuffer> parts;
                    if (split < 0) {
                        parts = Flux.range(0, wire.length)
                                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[]{wire[i]}));
                    } else {
                        parts = Flux.just(
                                DefaultDataBufferFactory.sharedInstance.wrap(Arrays.copyOfRange(wire, 0, split)),
                                DefaultDataBufferFactory.sharedInstance.wrap(Arrays.copyOfRange(wire, split, wire.length)));
                    }
                    var properties = ChatModelPropertiesTest.configured();
                    properties.setMaxEventBytes(limit);
                    Flux<ModelEvent> result = new OpenAiSseDecoder(properties).decode(parts)
                            .filter(e -> e instanceof ModelEvent.Delta || e instanceof ModelEvent.FinalUsage);
                    if (limit == required) {
                        StepVerifier.create(result).expectNext(new ModelEvent.Delta("answer"), new ModelEvent.FinalUsage(10, 2))
                                .verifyComplete();
                    } else {
                        StepVerifier.create(result).expectNext(new ModelEvent.Delta("answer"))
                                .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                                        .isEqualTo(ChatModelFailure.Kind.PAYLOAD_LIMIT)).verify();
                    }
                }
            }
        }
    }

    @Test
    void terminalCrWithoutLfFailsBeforeUsageAndReleasesPooledBufferWhenReservationCannotFit() {
        byte[] terminal = (": a\r\n".repeat(20) + "data: [DONE]\r\n\r").getBytes(StandardCharsets.UTF_8);
        var properties = ChatModelPropertiesTest.configured();
        properties.setMaxEventBytes(terminal.length);
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        var terminalBuffer = factory.allocateBuffer(terminal.length);
        terminalBuffer.write(terminal);
        TestPublisher<DataBuffer> source = TestPublisher.create();
        StepVerifier.create(new OpenAiSseDecoder(properties).decode(source.flux())
                        .filter(e -> e instanceof ModelEvent.Delta || e instanceof ModelEvent.FinalUsage))
                .then(() -> source.next(buffer(text("answer") + stop() + usage("10", "2"))))
                .expectNext(new ModelEvent.Delta("answer"))
                .then(() -> source.next(terminalBuffer))
                .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                        .isEqualTo(ChatModelFailure.Kind.PAYLOAD_LIMIT)).verify(Duration.ofSeconds(2));
        source.assertCancelled();
        assertThat(terminalBuffer.getNativeBuffer().refCnt()).isZero();
    }

    @Test
    void ordinaryCrEventAlsoRequiresTheReservedByteBeforePublishingText() {
        String frame = text("answer").replace("\n", "\r");
        var properties = ChatModelPropertiesTest.configured();
        properties.setMaxEventBytes(frame.getBytes(StandardCharsets.UTF_8).length);
        TestPublisher<DataBuffer> source = TestPublisher.create();
        StepVerifier.create(new OpenAiSseDecoder(properties).decode(source.flux()))
                .then(() -> source.next(buffer(frame)))
                .expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                        .isEqualTo(ChatModelFailure.Kind.PAYLOAD_LIMIT)).verify(Duration.ofSeconds(2));
        source.assertCancelled();
    }
}
