package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.chat.RetrievalFailure;
import com.zhishiku.kb.ai.telemetry.StreamTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class MultiTurnStreamTelemetryTest {
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "ERROR", "CANCELLED", "TIMEOUT"})
    void multiTurnTerminalMetricsFollowActualStreamOutcome(String outcome) {
        var fixture = new MultiTurnChatServiceTest();
        var meters = new SimpleMeterRegistry();
        try {
            if ("ERROR".equals(outcome)) {
                when(fixture.retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.error(new RetrievalFailure(RetrievalFailure.Kind.HTTP)));
            } else if (!"COMPLETED".equals(outcome)) {
                fixture.events = Flux.never();
            }
            var service = fixture.service(true);
            service.setTelemetry(new StreamTelemetry(meters));
            var state = service.prepare(9, "q", "r", 0, "token").block();
            var verifier = StepVerifier.withVirtualTime(() -> service.stream(state).filter(event -> event.comment() == null));
            if ("CANCELLED".equals(outcome)) {
                verifier.expectNextCount(1).thenCancel().verify();
            } else if ("TIMEOUT".equals(outcome)) {
                verifier.expectNextCount(1).thenAwait(Duration.ofSeconds(61))
                        .expectNextMatches(event -> "error".equals(event.event())).verifyComplete();
            } else {
                verifier.expectNextCount("COMPLETED".equals(outcome) ? 3 : 1).verifyComplete();
            }
            assertThat(meters.get("kb.chat.streams").tag("outcome", outcome).counter().count()).isEqualTo(1);
            assertThat(meters.get("kb.chat.started").counter().count()).isEqualTo(1);
            assertThat(meters.get("kb.chat.stream.duration").timer().count()).isEqualTo(1);
            if ("ERROR".equals(outcome)) {
                assertThat(meters.get("kb.chat.streams").counter().getId().getTag("error_code")).isEqualTo("SEARCH_UNAVAILABLE");
                assertThat(meters.find("kb.chat.first_event.delay").timer()).isNull();
            }
        } finally {
            meters.close();
        }
    }
}
