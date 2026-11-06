package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.ai.auth.PermitFixtures;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatObservationTest {
    @Test
    void noResultsAndPlaceholderNeverDispatchModel() {
        var retrieval = mock(RetrievalClient.class); var observer = mock(ChatObservation.class);
        when(retrieval.retrieve(any(), any())).thenReturn(Mono.just(List.of()));
        PermitFixtures.chat(retrieval).chat("问题", "token", observer).blockLast();
        verify(observer).result("NO_RESULTS"); verify(observer, never()).dispatch();
        when(retrieval.retrieve(any(), any())).thenReturn(Mono.just(List.of(new RetrievalClient.Citation(1, "t", 1, 2, "c"))));
        StepVerifier.withVirtualTime(() -> PermitFixtures.chat(retrieval).chat("问题", "token", observer))
                .thenAwait(java.time.Duration.ofSeconds(20)).thenConsumeWhile(event -> true).verifyComplete();
        verify(observer).result("PLACEHOLDER"); verify(observer, never()).dispatch();
    }
    @Test
    void persistedDispatchPrecedesExactlyOneModelSubscriptionAndFailedDispatchPreventsIt() {
        var retrieval = mock(RetrievalClient.class); var model = mock(ChatModelClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<ChatModelClient> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        when(retrieval.retrieve(any(), any())).thenReturn(Mono.just(List.of(new RetrievalClient.Citation(1L, "title", 1, 2L, "content"))));
        var calls = new AtomicInteger(); var dispatched = new AtomicInteger();
        when(model.streamEvents(any(), any())).thenAnswer(ignored -> {
            assertThat(dispatched).hasValue(1); calls.incrementAndGet();
            return Flux.just(ChatModelClient.StreamEvent.delta("ok"), ChatModelClient.StreamEvent.usage(0, 0));
        });
        var service = PermitFixtures.chat(retrieval, models);
        ChatObservation observer = new ChatObservation() {
            public Mono<Void> dispatch() { return Mono.fromRunnable(dispatched::incrementAndGet); }
        };
        StepVerifier.create(service.chat("问题", "token", observer)).expectNextCount(3).verifyComplete();
        assertThat(calls).hasValue(1);
        StepVerifier.create(service.chat("问题", "token", new ChatObservation() {
            public Mono<Void> dispatch() { return Mono.error(new IllegalStateException("private")); }
        })).expectNextMatches(event -> event instanceof ChatEvent.Citation).expectNextMatches(event -> event instanceof ChatEvent.Error).verifyComplete();
        assertThat(calls).hasValue(1);
    }
}
