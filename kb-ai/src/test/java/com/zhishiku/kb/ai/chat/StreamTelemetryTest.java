package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.ai.telemetry.StreamTelemetry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamTelemetryTest {
    @Test
    void controllerCountsCompletionSearchErrorAndCancellationWithoutDuplicateTerminal() {
        var clock = new MockClock();
        var meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try {
            var chat = mock(ChatService.class);
            var controller = new ChatController(chat, new AiProperties("http://app.invalid", 5, 1000, 60));
            controller.setTelemetry(new StreamTelemetry(meters));
            when(chat.chat(anyString(), anyString())).thenReturn(Flux.defer(() -> {
                clock.add(Duration.ofMillis(250));
                return Flux.just(new ChatEvent.Delta("secret-answer"), new ChatEvent.Done(1, 1, 1));
            }));
            var stream = controller.chat("secret-question", "secret-token");
            assertThat(meters.getMeters()).isEmpty();
            StepVerifier.create(stream).expectNextCount(2).verifyComplete();
            when(chat.chat(anyString(), anyString())).thenReturn(Flux.just(new ChatEvent.Error(RetrievalFailure.MESSAGE, RetrievalFailure.CODE)));
            StepVerifier.create(controller.chat("q", "t")).expectNextCount(1).verifyComplete();
            when(chat.chat(anyString(), anyString())).thenReturn(Flux.never());
            StepVerifier.withVirtualTime(() -> controller.chat("q", "t")).thenAwait(Duration.ofSeconds(2))
                    .expectNextCount(2).thenCancel().verify();
            assertThat(meters.get("kb.chat.started").counter().count()).isEqualTo(3);
            assertThat(meters.get("kb.chat.streams").tag("outcome", "COMPLETED").counter().count()).isEqualTo(1);
            assertThat(meters.get("kb.chat.streams").tags("outcome", "ERROR", "error_code", "SEARCH_UNAVAILABLE").counter().count()).isEqualTo(1);
            assertThat(meters.get("kb.chat.streams").tag("outcome", "CANCELLED").counter().count()).isEqualTo(1);
            assertThat(meters.get("kb.chat.first_event.delay").timer().count()).isEqualTo(1);
            assertThat(meters.get("kb.chat.first_event.delay").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250);
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags().toString()).doesNotContain("secret-"));
        } finally {
            meters.close();
        }
    }

    @Test
    void realChatServiceKeepsTimeoutCategoryWhenConvertingToSafeError() {
        var meters = new SimpleMeterRegistry();
        try {
            var retrieval = mock(RetrievalClient.class);
            when(retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.just(List.of(new RetrievalClient.Citation(1, "title", null, 2, "text"))));
            var beans = new StaticListableBeanFactory();
            beans.addBean("model", (ChatModelClient) (question, citations) -> Flux.never());
            var props = new AiProperties("http://app.invalid", 5, 100000, 1);
            var controller = new ChatController(new ChatService(retrieval, beans.getBeanProvider(ChatModelClient.class), props), props);
            controller.setTelemetry(new StreamTelemetry(meters));
            StepVerifier.withVirtualTime(() -> controller.chat("q", "t")).expectNextCount(1)
                    .thenAwait(Duration.ofSeconds(2)).expectNextMatches(event -> "error".equals(event.event())).verifyComplete();
            assertThat(meters.get("kb.chat.streams").tag("outcome", "TIMEOUT").counter().count()).isEqualTo(1);
            assertThat(meters.get("kb.chat.stream.duration").tag("outcome", "TIMEOUT").timer().count()).isEqualTo(1);
        } finally {
            meters.close();
        }
    }
}
