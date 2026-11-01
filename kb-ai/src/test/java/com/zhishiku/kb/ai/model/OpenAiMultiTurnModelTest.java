package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.conversation.ConversationBudget;
import com.zhishiku.kb.ai.conversation.MultiTurnModel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenAiMultiTurnModelTest {
    private final ChatPromptRenderer renderer = mock(ChatPromptRenderer.class);
    private final OpenAiChatTransport transport = mock(OpenAiChatTransport.class);
    private final ChatBudgetBinding budget = mock(ChatBudgetBinding.class);
    private final PreparedChatRequest request = mock(PreparedChatRequest.class);
    private final MultiTurnModel.Input input = new MultiTurnModel.Input(ConversationBudget.INSTRUCTION,
            List.of(new MultiTurnModel.Message("user", "问题")), List.of(), 2048);
    private final OpenAiMultiTurnModel model = new OpenAiMultiTurnModel(renderer, transport, budget, 12000);

    OpenAiMultiTurnModelTest() {
        when(renderer.render(input)).thenReturn(request);
        when(request.maxOutputTokens()).thenReturn(2048);
    }

    @Test
    void absentBudgetRejectsEvenDirectGenerationBeforeRenderingOrTransport() {
        assertThat(model.supportsTokenUpperBound()).isFalse();
        assertThat(model.contextWindowTokens()).isZero();
        assertThatThrownBy(() -> model.inputTokenUpperBound(input)).hasMessage("BUDGET_UNAVAILABLE");
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        verifyNoInteractions(renderer, transport);
    }

    @Test
    void forwardsSamePreparedRequestAndLongUsageExactly() {
        when(budget.available()).thenReturn(true);
        when(budget.window()).thenReturn(16000L);
        when(budget.upperBound(request)).thenReturn(9000L);
        when(transport.stream(request)).thenReturn(Flux.just(new ModelEvent.Delta("答"),
                new ModelEvent.FinalUsage(2147483648L, 0)));
        assertThat(model.inputTokenUpperBound(input)).isEqualTo(9000);
        verifyNoInteractions(transport);
        var generated = model.generate(input);
        StepVerifier.create(generated).expectNext(new MultiTurnModel.Event("答", false, null, null),
                new MultiTurnModel.Event(null, true, 2147483648L, 0L)).verifyComplete();
        StepVerifier.create(generated).expectErrorMessage("ALREADY_SUBSCRIBED").verify();
        verify(transport).stream(request);
    }

    @Test
    void reservationAndModelWindowBothConstrainGenerationWithoutOverflow() {
        when(budget.available()).thenReturn(true);
        when(budget.window()).thenReturn(16000L);
        when(budget.upperBound(request)).thenReturn(9953L);
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        when(budget.upperBound(request)).thenReturn(Long.MAX_VALUE);
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        when(budget.window()).thenReturn(2047L);
        when(budget.upperBound(request)).thenReturn(0L);
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        verifyNoInteractions(transport);
    }

    @Test
    void missingUsageStaysUnknownAndFailureOrCancelNeverBecomesComplete() {
        when(budget.available()).thenReturn(true);
        when(budget.window()).thenReturn(16000L);
        when(budget.upperBound(request)).thenReturn(10L);
        when(transport.stream(request)).thenReturn(Flux.just(new ModelEvent.Delta("答")));
        StepVerifier.create(model.generate(input)).expectNext(new MultiTurnModel.Event("答", false, null, null))
                .verifyComplete();
        when(transport.stream(request)).thenReturn(Flux.error(new ChatModelFailure(ChatModelFailure.Kind.INCOMPLETE_STREAM)));
        StepVerifier.create(model.generate(input)).expectErrorMessage("INCOMPLETE_STREAM").verify();
        TestPublisher<ModelEvent> publisher = TestPublisher.create();
        when(transport.stream(request)).thenReturn(publisher.flux());
        StepVerifier.create(model.generate(input)).then(() -> publisher.next(new ModelEvent.Delta("前缀")))
                .expectNextCount(1).thenCancel().verify();
        publisher.assertCancelled();
    }
}
