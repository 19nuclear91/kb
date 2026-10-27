package com.zhishiku.kb.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仅使用内存模型和虚拟时钟检查生命周期，不建立 SSE 网络连接。
 */
class ChatServiceLifecycleTest {

    private static final AiProperties PROPS = new AiProperties("http://app.invalid", 5, 60_000L, 1);
    private static final RetrievalClient.Citation SOURCE = new RetrievalClient.Citation(10L, "说明", 2,
            20L, "正文", 30L, 7, 4, "权限", "PARAGRAPH", 10, 18, 120, 340, "section:access", false);
    private static final ChatEvent.Error SAFE_ERROR = new ChatEvent.Error("处理请求时出错，请稍后重试。");

    @Test
    void placeholderKeepsVersionedReferenceAndCompletesWithZeroUsage() {
        RetrievalClient retrieval = retrieval(List.of(SOURCE));
        ChatService service = new ChatService(retrieval, null, PROPS);

        StepVerifier.withVirtualTime(() -> service.chat("问题", "token").collectList())
                .thenAwait(Duration.ofSeconds(10))
                .assertNext(events -> {
                    assertThat(events.getFirst()).isEqualTo(ChatEvent.Citation.from(SOURCE));
                    assertThat(events.subList(1, events.size() - 1))
                            .allMatch(ChatEvent.Delta.class::isInstance);
                    String answer = events.stream().filter(ChatEvent.Delta.class::isInstance)
                            .map(ChatEvent.Delta.class::cast).map(ChatEvent.Delta::text)
                            .reduce("", String::concat);
                    assertThat(answer).contains("占位应答", "说明");
                    assertZeroUsage(events.getLast());
                })
                .verifyComplete();

        verify(retrieval).retrieve("问题", "token");
    }

    @Test
    void placeholderDoesNotTurnMissingTitleIntoLiteralNullOrFail() {
        var source = new RetrievalClient.Citation(10L, null, null, 20L, "正文");
        ChatService service = new ChatService(retrieval(List.of(source)), null, PROPS);

        StepVerifier.withVirtualTime(() -> service.chat("问题", "token").collectList())
                .thenAwait(Duration.ofSeconds(10))
                .assertNext(events -> {
                    assertThat(events.getFirst()).isEqualTo(ChatEvent.Citation.from(source));
                    assertThat(events).noneMatch(ChatEvent.Error.class::isInstance);
                    String answer = events.stream().filter(ChatEvent.Delta.class::isInstance)
                            .map(ChatEvent.Delta.class::cast).map(ChatEvent.Delta::text)
                            .reduce("", String::concat);
                    assertThat(answer).doesNotContain("null");
                    assertZeroUsage(events.getLast());
                })
                .verifyComplete();
    }

    @Test
    void emptyRetrievalKeepsUniformReplyAndDoesNotCallModel() {
        RetrievalClient retrieval = retrieval(List.of());
        ObjectProvider<ChatModelClient> models = mock(ObjectProvider.class);
        ChatService service = new ChatService(retrieval, models, PROPS);

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(new ChatEvent.Delta("未找到与问题相关的内容。"))
                .assertNext(ChatServiceLifecycleTest::assertZeroUsage)
                .verifyComplete();
        StepVerifier.create(service.chat("问题", null))
                .expectNext(new ChatEvent.Delta("未找到与问题相关的内容。"))
                .assertNext(ChatServiceLifecycleTest::assertZeroUsage)
                .verifyComplete();

        verify(models, times(0)).getIfAvailable();
    }

    @Test
    void modelFailureAfterReferenceEndsWithSafeErrorAndNoDone() {
        ChatModelClient model = (question, citations) -> Flux.just("部分回答")
                .concatWith(Flux.error(new IllegalStateException("secret prompt fragment")));
        ChatService service = service(model);

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(ChatEvent.Citation.from(SOURCE))
                .expectNext(new ChatEvent.Delta("部分回答"))
                .expectNext(SAFE_ERROR)
                .verifyComplete();
    }

    @Test
    void modelTimeoutKeepsReferenceThenErrorsAndCancelsModel() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ChatModelClient model = (question, citations) -> Flux.<String>never()
                .doOnCancel(() -> cancelled.set(true));
        ChatService service = service(model);

        StepVerifier.withVirtualTime(() -> service.chat("问题", "token"))
                .expectNext(ChatEvent.Citation.from(SOURCE))
                .thenAwait(Duration.ofSeconds(2))
                .expectNext(SAFE_ERROR)
                .verifyComplete();

        assertThat(cancelled.get()).isTrue();
    }

    @Test
    void cancellationAfterReferenceAndDeltaStopsModelWithoutTerminalEvent() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ChatModelClient model = (question, citations) -> Flux.just("部分回答")
                .concatWith(Flux.never()).doOnCancel(() -> cancelled.set(true));
        ChatService service = service(model);

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(ChatEvent.Citation.from(SOURCE))
                .expectNext(new ChatEvent.Delta("部分回答"))
                .thenCancel()
                .verify();

        assertThat(cancelled.get()).isTrue();
    }

    @Test
    void finalCumulativeUsageIsNotSummedOrExposedAsAnExtraDelta() {
        ChatModelClient model = mock(ChatModelClient.class);
        when(model.streamEvents("问题", List.of(SOURCE))).thenReturn(Flux.just(
                ChatModelClient.StreamEvent.delta("答"), ChatModelClient.StreamEvent.usage(10, 2),
                ChatModelClient.StreamEvent.usage(37, 11)));
        ChatService service = service(model);

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(ChatEvent.Citation.from(SOURCE))
                .expectNext(new ChatEvent.Delta("答"))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(ChatEvent.Done.class);
                    var done = (ChatEvent.Done) event;
                    assertThat(done.promptTokens()).isEqualTo(37);
                    assertThat(done.completionTokens()).isEqualTo(11);
                })
                .verifyComplete();
    }

    private static ChatService service(ChatModelClient model) {
        ObjectProvider<ChatModelClient> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        return new ChatService(retrieval(List.of(SOURCE)), models, PROPS);
    }

    private static RetrievalClient retrieval(List<RetrievalClient.Citation> sources) {
        RetrievalClient retrieval = mock(RetrievalClient.class);
        when(retrieval.retrieve(any(), any())).thenReturn(Mono.just(sources));
        return retrieval;
    }

    private static void assertZeroUsage(ChatEvent event) {
        assertThat(event).isInstanceOf(ChatEvent.Done.class);
        var done = (ChatEvent.Done) event;
        assertThat(done.promptTokens()).isZero();
        assertThat(done.completionTokens()).isZero();
    }
}
