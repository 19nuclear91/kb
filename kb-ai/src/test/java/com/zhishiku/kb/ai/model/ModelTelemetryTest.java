package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.telemetry.ModelTelemetry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelTelemetryTest {
    @Test
    void completionCountsKnownAndUnknownUsageAndFirstTextExactlyOnce() {
        var clock = new MockClock();
        var meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try {
            for (boolean usage : new boolean[]{true, false}) {
                Flux<ModelEvent> events = Flux.defer(() -> {
                    clock.add(Duration.ofMillis(200));
                    return Flux.just(new ModelEvent.Delta("secret-answer"), new ModelEvent.Delta("more"));
                });
                if (usage) {
                    events = events.concatWithValues(new ModelEvent.FinalUsage(10, 2));
                }
                var stream = transport(meters, events).stream(OpenAiChatTransportTest.request());
                StepVerifier.create(stream).expectNextCount(usage ? 3 : 2).verifyComplete();
                StepVerifier.create(stream).expectError(ChatModelFailure.class).verify();
            }
            assertThat(meters.get("kb.model.calls").tag("outcome", "COMPLETED").counter().count()).isEqualTo(2);
            assertThat(meters.get("kb.model.call.duration").timer().count()).isEqualTo(2);
            assertThat(meters.get("kb.model.first_text.delay").timer().count()).isEqualTo(2);
            assertThat(meters.get("kb.model.first_text.delay").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(400);
            for (String status : List.of("KNOWN", "UNKNOWN")) {
                assertThat(meters.get("kb.model.usage").tag("status", status).counter().count()).isEqualTo(1);
            }
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isIn("outcome", "status");
                assertThat(tag.getValue()).isIn("COMPLETED", "KNOWN", "UNKNOWN");
            }));
        } finally {
            meters.close();
        }
    }

    @Test
    void failureTimeoutAndCancellationHaveBoundedOutcomesAndUnknownUsage() {
        var meters = new SimpleMeterRegistry();
        try {
            for (var kind : List.of(ChatModelFailure.Kind.HTTP_REJECTED, ChatModelFailure.Kind.PROTOCOL_INVALID,
                    ChatModelFailure.Kind.PAYLOAD_LIMIT)) {
                StepVerifier.create(transport(meters, Flux.error(new ChatModelFailure(kind))).stream(OpenAiChatTransportTest.request()))
                        .expectError(ChatModelFailure.class).verify();
            }
            StepVerifier.withVirtualTime(() -> transport(meters, Flux.never()).stream(OpenAiChatTransportTest.request()))
                    .thenAwait(Duration.ofSeconds(61)).expectErrorSatisfies(error -> assertThat(((ChatModelFailure) error).kind())
                            .isEqualTo(ChatModelFailure.Kind.TIMEOUT)).verify();
            StepVerifier.create(transport(meters, Flux.never()).stream(OpenAiChatTransportTest.request()))
                    .thenCancel().verify();
            for (String outcome : List.of("UPSTREAM_ERROR", "PROTOCOL_ERROR", "LIMIT", "TIMEOUT", "CANCELLED")) {
                assertThat(meters.get("kb.model.calls").tag("outcome", outcome).counter().count()).isEqualTo(1);
                assertThat(meters.get("kb.model.call.duration").tag("outcome", outcome).timer().count()).isEqualTo(1);
            }
            assertThat(meters.get("kb.model.usage").tag("status", "UNKNOWN").counter().count()).isEqualTo(5);
            assertThat(meters.find("kb.model.first_text.delay").timer()).isNull();
        } finally {
            meters.close();
        }
    }

    private static OpenAiChatTransport transport(SimpleMeterRegistry meters, Flux<ModelEvent> events) {
        var decoder = mock(OpenAiSseDecoder.class);
        when(decoder.decode(any())).thenReturn(events);
        var client = WebClient.builder().exchangeFunction(request -> Mono.just(
                OpenAiChatTransportTest.response(200, "text/event-stream", Flux.empty()))).build();
        return new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, decoder, new ModelTelemetry(meters));
    }
}
