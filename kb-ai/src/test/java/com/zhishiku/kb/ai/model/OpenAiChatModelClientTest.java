package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.chat.ChatModelClient;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenAiChatModelClientTest {
    private final ChatPromptRenderer renderer = mock(ChatPromptRenderer.class);
    private final OpenAiChatTransport transport = mock(OpenAiChatTransport.class);
    private final PreparedChatRequest request = mock(PreparedChatRequest.class);
    private final List<RetrievalClient.Citation> materials = List.of(new RetrievalClient.Citation(1, "title", 2, 3, "材料"));
    private final OpenAiChatModelClient client = new OpenAiChatModelClient(renderer, transport, 2048);

    OpenAiChatModelClientTest() {
        when(renderer.single("问题", materials, 2048)).thenReturn(request);
    }

    @Test
    void mapsDeltaAndExactUsageOnceAfterLazyPreparation() {
        when(transport.stream(request)).thenReturn(Flux.just(new ModelEvent.Delta("答"),
                new ModelEvent.FinalUsage(Integer.MAX_VALUE, 0)));
        var stream = client.streamEvents("问题", materials);
        verifyNoInteractions(renderer, transport);
        StepVerifier.create(stream).expectNext(ChatModelClient.StreamEvent.delta("答"),
                ChatModelClient.StreamEvent.usage(Integer.MAX_VALUE, 0)).verifyComplete();
        StepVerifier.create(stream).expectErrorMessage("ALREADY_SUBSCRIBED").verify();
        verify(renderer).single("问题", materials, 2048);
        verify(transport).stream(request);
    }

    @Test
    void legacyTextMethodDoesNotCreateAnotherSubscriptionForUsage() {
        when(transport.stream(request)).thenReturn(Flux.just(new ModelEvent.Delta("答"), new ModelEvent.FinalUsage(10, 2)));
        StepVerifier.create(client.stream("问题", materials)).expectNext("答").verifyComplete();
        verify(transport).stream(request);
    }

    @Test
    void missingUsageRemainsMissingAndOverflowCannotWrapOrClamp() {
        when(transport.stream(request)).thenReturn(Flux.just(new ModelEvent.Delta("答")));
        StepVerifier.create(client.streamEvents("问题", materials))
                .expectNext(ChatModelClient.StreamEvent.delta("答")).verifyComplete();
        when(transport.stream(request)).thenReturn(Flux.just(new ModelEvent.FinalUsage(2147483648L, 0)));
        StepVerifier.create(client.streamEvents("问题", materials)).expectErrorMessage("PROTOCOL_INVALID").verify();
        when(transport.stream(request)).thenReturn(Flux.just(new ModelEvent.FinalUsage(-1, 0)));
        StepVerifier.create(client.streamEvents("问题", materials)).expectErrorMessage("PROTOCOL_INVALID").verify();
    }

    @Test
    void propagatesFailureAndCancellationWithoutPlaceholderOrRetry() {
        when(transport.stream(request)).thenReturn(Flux.just((ModelEvent) new ModelEvent.Delta("前缀"))
                .concatWith(Flux.error(new ChatModelFailure(ChatModelFailure.Kind.OUTPUT_TRUNCATED))));
        StepVerifier.create(client.stream("问题", materials)).expectNext("前缀").expectErrorMessage("OUTPUT_TRUNCATED").verify();
        TestPublisher<ModelEvent> publisher = TestPublisher.create();
        when(transport.stream(request)).thenReturn(publisher.flux());
        StepVerifier.create(client.streamEvents("问题", materials))
                .then(() -> publisher.next(new ModelEvent.Delta("字")))
                .expectNext(ChatModelClient.StreamEvent.delta("字")).thenCancel().verify();
        publisher.assertCancelled();
    }
}
