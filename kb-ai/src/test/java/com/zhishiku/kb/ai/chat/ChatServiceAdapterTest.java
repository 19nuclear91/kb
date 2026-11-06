package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.ai.auth.PermitFixtures;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceAdapterTest {

    @Test
    void injectedChatModelStreamsDeltasAfterCitations() {
        RetrievalClient retrieval = mock(RetrievalClient.class);
        ObjectProvider<ChatModelClient> clients = mock(ObjectProvider.class);
        ChatModelClient model = (question, citations) -> Flux.just("答", "案");
        when(retrieval.retrieve(anyString(), any())).thenReturn(Mono.just(List.of(
                new RetrievalClient.Citation(10L, "说明", 2, 20L, "正文"))));
        when(clients.getIfAvailable()).thenReturn(model);

        ChatService service = PermitFixtures.chat(retrieval, clients);

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(new ChatEvent.Citation(10L, "说明", 2, 20L))
                .expectNext(new ChatEvent.Delta("答"))
                .expectNext(new ChatEvent.Delta("案"))
                .assertNext(event -> assertThat(event).isInstanceOf(ChatEvent.Done.class))
                .verifyComplete();
    }

    @Test
    void modelUsageIsPropagatedToDoneForQuotaSettlement() {
        RetrievalClient retrieval = mock(RetrievalClient.class);
        ObjectProvider<ChatModelClient> clients = mock(ObjectProvider.class);
        ChatModelClient model = new ChatModelClient() {
            @Override
            public Flux<String> stream(String question, List<RetrievalClient.Citation> citations) {
                return Flux.just("答");
            }

            @Override
            public Flux<StreamEvent> streamEvents(String question,
                                                  List<RetrievalClient.Citation> citations) {
                return Flux.just(StreamEvent.delta("答"), StreamEvent.usage(37, 11));
            }
        };
        when(retrieval.retrieve(anyString(), any())).thenReturn(Mono.just(List.of(
                new RetrievalClient.Citation(10L, "说明", 2, 20L, "正文"))));
        when(clients.getIfAvailable()).thenReturn(model);

        ChatService service = PermitFixtures.chat(retrieval, clients);

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(new ChatEvent.Citation(10L, "说明", 2, 20L))
                .expectNext(new ChatEvent.Delta("答"))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(ChatEvent.Done.class);
                    ChatEvent.Done done = (ChatEvent.Done) event;
                    assertThat(done.promptTokens()).isEqualTo(37);
                    assertThat(done.completionTokens()).isEqualTo(11);
                })
                .verifyComplete();
    }

    @Test
    void fullContextKeepsVersionAndEverySourceFieldWithoutTruncation() {
        List<RetrievalClient.Citation> sources = List.of(source(20L, "正文"), source(21L, "材料"));
        var captured = new AtomicReference<List<RetrievalClient.Citation>>();
        ChatService service = capturingService(sources, 10, captured);

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(ChatEvent.Citation.from(sources.get(0)))
                .expectNext(ChatEvent.Citation.from(sources.get(1)))
                .expectNext(new ChatEvent.Delta("答"))
                .assertNext(event -> assertThat(event).isInstanceOf(ChatEvent.Done.class))
                .verifyComplete();

        assertThat(captured.get()).containsExactlyElementsOf(sources);
        assertThat(captured.get()).allMatch(citation -> !citation.contextTruncated());
    }

    @Test
    void partialContextKeepsUnicodeAndMetadataWhileExcludedChunkEmitsNoCitation() {
        List<RetrievalClient.Citation> sources = List.of(source(20L, "XY"),
                source(21L, "A\uD83D\uDE80B"), source(22L, "excluded"));
        var captured = new AtomicReference<List<RetrievalClient.Citation>>();
        ChatService service = capturingService(sources, 4, captured);
        var emitted = new AtomicReference<ChatEvent.Citation>();

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(ChatEvent.Citation.from(sources.get(0)))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(ChatEvent.Citation.class);
                    emitted.set((ChatEvent.Citation) event);
                    assertThat(emitted.get()).usingRecursiveComparison()
                            .ignoringFields("contextTruncated")
                            .isEqualTo(ChatEvent.Citation.from(sources.get(1)));
                    assertThat(emitted.get().contextTruncated()).isTrue();
                })
                .expectNext(new ChatEvent.Delta("答"))
                .assertNext(event -> assertThat(event).isInstanceOf(ChatEvent.Done.class))
                .verifyComplete();

        assertThat(captured.get()).hasSize(2);
        assertThat(captured.get().get(0)).isEqualTo(sources.get(0));
        RetrievalClient.Citation truncated = captured.get().get(1);
        assertThat(truncated).usingRecursiveComparison()
                .ignoringFields("content", "contextTruncated").isEqualTo(sources.get(1));
        assertThat(truncated.content()).isEqualTo("A\uD83D\uDE80");
        assertThat(truncated.content().codePointCount(0, truncated.content().length())).isEqualTo(2);
        assertThat(truncated.contextTruncated()).isTrue();
        assertThat(emitted.get()).isEqualTo(ChatEvent.Citation.from(truncated));
        assertThat(sources.get(1).content()).isEqualTo("A\uD83D\uDE80B");
        assertThat(sources.get(1).contextTruncated()).isFalse();
    }

    @Test
    void exactCodePointBudgetDoesNotMarkTruncationAndDropsFollowingChunk() {
        List<RetrievalClient.Citation> sources = List.of(source(20L, "\uD83D\uDE80"), source(21L, "excluded"));
        var captured = new AtomicReference<List<RetrievalClient.Citation>>();
        ChatService service = capturingService(sources, 1, captured);

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(ChatEvent.Citation.from(sources.get(0)))
                .expectNext(new ChatEvent.Delta("答"))
                .assertNext(event -> assertThat(event).isInstanceOf(ChatEvent.Done.class))
                .verifyComplete();

        assertThat(captured.get()).containsExactly(sources.get(0));
        assertThat(captured.get().getFirst().contextTruncated()).isFalse();
    }

    @Test
    void nullAndEmptyBodiesDoNotConsumeBudgetOrAcquireFakeTruncation() {
        List<RetrievalClient.Citation> sources = List.of(source(20L, null), source(21L, ""), source(22L, "X"));
        var captured = new AtomicReference<List<RetrievalClient.Citation>>();
        ChatService service = capturingService(sources, 1, captured);

        StepVerifier.create(service.chat("问题", "token"))
                .expectNext(ChatEvent.Citation.from(sources.get(0)))
                .expectNext(ChatEvent.Citation.from(sources.get(1)))
                .expectNext(ChatEvent.Citation.from(sources.get(2)))
                .expectNext(new ChatEvent.Delta("答"))
                .assertNext(event -> assertThat(event).isInstanceOf(ChatEvent.Done.class))
                .verifyComplete();

        assertThat(captured.get()).extracting(RetrievalClient.Citation::content).containsExactly("", "", "X");
        for (int index = 0; index < sources.size(); index++) {
            assertThat(captured.get().get(index)).usingRecursiveComparison()
                    .ignoringFields("content").isEqualTo(sources.get(index));
        }
    }

    @Test
    void legacyReferenceStaysUnversionedWhenItsBodyIsTruncated() {
        var captured = new AtomicReference<List<RetrievalClient.Citation>>();
        var legacy = new RetrievalClient.Citation(10L, "旧来源", null, 20L, "AB");
        ChatService service = capturingService(List.of(legacy), 1, captured);

        StepVerifier.create(service.chat("问题", "token"))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(ChatEvent.Citation.class);
                    var citation = (ChatEvent.Citation) event;
                    assertThat(citation.provenanceStatus()).isEqualTo(ChatEvent.ProvenanceStatus.UNVERSIONED);
                    assertThat(citation.versionId()).isNull();
                    assertThat(citation.contextTruncated()).isTrue();
                })
                .expectNext(new ChatEvent.Delta("答"))
                .assertNext(event -> assertThat(event).isInstanceOf(ChatEvent.Done.class))
                .verifyComplete();

        assertThat(captured.get().getFirst().content()).isEqualTo("A");
        assertThat(captured.get().getFirst()).usingRecursiveComparison()
                .ignoringFields("content", "contextTruncated").isEqualTo(legacy);
    }

    private static RetrievalClient.Citation source(long chunkId, String content) {
        return new RetrievalClient.Citation(10L, "说明", 2, chunkId, content, 30L, 7,
                Math.toIntExact(chunkId - 20), "设计 / 权限", "PARAGRAPH", 10, 18, 120, 340,
                "section:access", false);
    }

    private static ChatService capturingService(List<RetrievalClient.Citation> sources, int budget,
                                                AtomicReference<List<RetrievalClient.Citation>> captured) {
        RetrievalClient retrieval = mock(RetrievalClient.class);
        ObjectProvider<ChatModelClient> clients = mock(ObjectProvider.class);
        ChatModelClient model = (question, citations) -> {
            assertThat(question).isEqualTo("问题");
            assertThat(captured.compareAndSet(null, citations)).isTrue();
            return Flux.just("答");
        };
        when(retrieval.retrieve("问题", "token")).thenReturn(Mono.just(sources));
        when(clients.getIfAvailable()).thenReturn(model);
        AiProperties props = new AiProperties("http://app.invalid", 5, 15_000, 30,
                8_000, budget, 60, 8);
        return PermitFixtures.chat(retrieval, clients, props);
    }
}
