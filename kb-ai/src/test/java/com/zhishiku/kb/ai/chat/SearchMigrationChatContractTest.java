package com.zhishiku.kb.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 进程内适配契约：检索传输迁移不改变引用身份和 SSE 内容。
 */
class SearchMigrationChatContractTest {
    private static final AiProperties PROPS =
            new AiProperties("http://app.invalid", 5, 60_000, 30);

    @Test
    void versionedCitationAndSourceFieldsPrecedeModelDeltasAndDone() {
        RetrievalClient retrieval = mock(RetrievalClient.class);
        ObjectProvider<ChatModelClient> providers = mock(ObjectProvider.class);
        ChatModelClient model = (question, citations) -> Flux.just("答", "案");
        RetrievalClient.Citation source = new RetrievalClient.Citation(10L, "说明", 2,
                20L, "正文", 30L, 7, 4, "权限", "PARAGRAPH", 10, 18,
                120, 340, "section:access", false);
        when(retrieval.retrieve("问题", "token")).thenReturn(Mono.just(List.of(source)));
        when(providers.getIfAvailable()).thenReturn(model);
        ChatController controller = new ChatController(new ChatService(retrieval, providers, PROPS), PROPS);

        StepVerifier.create(controller.chat("问题", "token"))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("citation");
                    assertThat(sse.data()).isEqualTo(ChatEvent.Citation.from(source));
                })
                .expectNextMatches(sse -> "delta".equals(sse.event())
                        && new ChatEvent.Delta("答").equals(sse.data()))
                .expectNextMatches(sse -> "delta".equals(sse.event())
                        && new ChatEvent.Delta("案").equals(sse.data()))
                .expectNextMatches(sse -> "done".equals(sse.event()))
                .verifyComplete();
    }

    @Test
    void emptyAuthorizedResultsKeepUniformReplyWithoutModelCall() {
        RetrievalClient retrieval = mock(RetrievalClient.class);
        ObjectProvider<ChatModelClient> providers = mock(ObjectProvider.class);
        when(retrieval.retrieve("问题", "token")).thenReturn(Mono.just(List.of()));
        ChatController controller = new ChatController(new ChatService(retrieval, providers, PROPS), PROPS);

        StepVerifier.create(controller.chat("问题", "token"))
                .expectNextMatches(sse -> "delta".equals(sse.event())
                        && new ChatEvent.Delta("未找到与问题相关的内容。").equals(sse.data()))
                .expectNextMatches(sse -> "done".equals(sse.event()))
                .verifyComplete();
        verifyNoInteractions(providers);
    }
}
