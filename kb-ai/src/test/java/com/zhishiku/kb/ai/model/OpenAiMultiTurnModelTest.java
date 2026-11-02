package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.conversation.ConversationBudget;
import com.zhishiku.kb.ai.conversation.MultiTurnModel;
import com.zhishiku.kb.ai.conversation.InputBudgetExceededException;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        when(budget.capability()).thenReturn(BudgetCapability.unavailable());
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
        when(budget.capability()).thenReturn(new BudgetCapability(BudgetKind.VERIFIED, 16000, 0));
        when(budget.count(request)).thenReturn(new InputTokenCount(BudgetKind.VERIFIED, 9000));
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
        when(budget.capability()).thenReturn(new BudgetCapability(BudgetKind.VERIFIED, 16000, 0));
        when(budget.count(request)).thenReturn(new InputTokenCount(BudgetKind.VERIFIED, 9953));
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        when(budget.count(request)).thenReturn(new InputTokenCount(BudgetKind.VERIFIED, Long.MAX_VALUE));
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        when(budget.capability()).thenReturn(new BudgetCapability(BudgetKind.VERIFIED, 2047, 0));
        when(budget.count(request)).thenReturn(new InputTokenCount(BudgetKind.VERIFIED, 0));
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        verifyNoInteractions(transport);
    }

    @Test
    void missingUsageStaysUnknownAndFailureOrCancelNeverBecomesComplete() {
        when(budget.capability()).thenReturn(new BudgetCapability(BudgetKind.VERIFIED, 16000, 0));
        when(budget.count(request)).thenReturn(new InputTokenCount(BudgetKind.VERIFIED, 10));
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

    @Test
    void estimatedModeCannotPassTheLegacyUpperBoundEntry() {
        var estimate = ConfiguredEstimatedTokenBudgetTest.estimator(ConfiguredEstimatedTokenBudgetTest.settings());
        var estimated = new OpenAiMultiTurnModel(new ChatPromptRenderer(ChatModelPropertiesTest.configured()),
                transport, estimate, 12000);
        assertThat(estimated.supportsTokenUpperBound()).isFalse();
        assertThat(estimated.contextWindowTokens()).isEqualTo(16000);
        assertThat(estimated.budgetCapability().kind()).isEqualTo(BudgetKind.ESTIMATED);
        assertThat(estimated.countInputTokens(input).kind()).isEqualTo(BudgetKind.ESTIMATED);
        assertThatThrownBy(() -> estimated.inputTokenUpperBound(input)).hasMessage("BUDGET_UNAVAILABLE");
        verifyNoInteractions(transport);
    }

    @Test
    void estimatedBoundaryIsCheckedOnlyOnSubscriptionAndSendsTheExactRequest() {
        when(budget.capability()).thenReturn(new BudgetCapability(BudgetKind.ESTIMATED, 16000, 512));
        when(budget.count(request)).thenReturn(new InputTokenCount(BudgetKind.ESTIMATED, 9440));
        when(transport.stream(request)).thenReturn(Flux.just(new ModelEvent.Delta("answer")));
        var generated = model.generate(input);
        verifyNoInteractions(renderer, transport);
        StepVerifier.create(generated).expectNextCount(1).verifyComplete();
        verify(renderer).render(input);
        verify(transport).stream(request);
        StepVerifier.create(generated).expectErrorMessage("ALREADY_SUBSCRIBED").verify();
    }

    @Test
    void estimatedOverLimitSmallerWindowAndKindMismatchNeverSend() {
        when(budget.capability()).thenReturn(new BudgetCapability(BudgetKind.ESTIMATED, 16000, 512));
        when(budget.count(request)).thenReturn(new InputTokenCount(BudgetKind.ESTIMATED, 9441));
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        when(budget.capability()).thenReturn(new BudgetCapability(BudgetKind.ESTIMATED, 10000, 512));
        when(budget.count(request)).thenReturn(new InputTokenCount(BudgetKind.ESTIMATED, 7441));
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        when(budget.count(request)).thenReturn(new InputTokenCount(BudgetKind.VERIFIED, 0));
        assertThatThrownBy(() -> model.countInputTokens(input)).hasMessage("BUDGET_UNAVAILABLE");
        StepVerifier.create(model.generate(input)).expectErrorMessage("BUDGET_UNAVAILABLE").verify();
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @CsvSource({"9039,9039,9440", "9040,4520,4921"})
    void realEstimatorAndRendererUseTheSameBoundaryInSelectionAndSending(int supplied, int retained, long expectedCount) {
        var properties = ChatModelPropertiesTest.configured();
        properties.getBudget().setMode("estimated");
        properties.getBudget().setContextWindowTokens(16000);
        var policy = new ConfiguredEstimatedTokenBudget(ChatModelIdentity.of(properties), properties.getBudget());
        var actual = new OpenAiMultiTurnModel(new ChatPromptRenderer(properties), transport, policy, 12000);
        var citation = new RetrievalClient.Citation(1, "t", 2, 3, "A".repeat(supplied), 4L,
                null, null, null, null, null, null, null, null, null, false);
        var plan = new ConversationBudget(new ConversationProperties()).select(actual, "Q", List.of(), List.of(citation));
        assertThat(plan.input().materials().getFirst().content()).hasSize(retained);
        assertThat(actual.countInputTokens(plan.input()).tokens()).isEqualTo(expectedCount);
        verifyNoInteractions(transport);
        when(transport.stream(any())).thenReturn(Flux.just(new ModelEvent.Delta("answer")));
        StepVerifier.create(actual.generate(plan.input())).expectNextCount(1).verifyComplete();
        var sent = ArgumentCaptor.forClass(PreparedChatRequest.class);
        verify(transport).stream(sent.capture());
        assertThat(policy.count(sent.getValue()).tokens()).isEqualTo(expectedCount);
        assertThat(sent.getValue().messages().getLast().content()).isEqualTo("Q");
        assertThat(sent.getValue().maxOutputTokens()).isEqualTo(2048);
    }

    @Test
    void realEstimatorRejectsWhitespaceOnlyMaterialBeforeTransport() {
        var properties = ChatModelPropertiesTest.configured();
        properties.getBudget().setMode("estimated");
        properties.getBudget().setContextWindowTokens(2962);
        properties.validate(true, 60, 12000, 2048);
        var policy = new ConfiguredEstimatedTokenBudget(ChatModelIdentity.of(properties), properties.getBudget());
        var actual = new OpenAiMultiTurnModel(new ChatPromptRenderer(properties), transport, policy, 12000);
        var citation = new RetrievalClient.Citation(1, "t", 2, 3, "   文", 4L,
                null, null, null, null, null, null, null, null, null, false);
        var supplied = new MultiTurnModel.Input(ConversationBudget.INSTRUCTION,
                List.of(new MultiTurnModel.Message("user", "Q")), List.of(citation), 2048);
        assertThat(actual.countInputTokens(supplied).tokens()).isEqualTo(407);
        assertThatThrownBy(() -> new ConversationBudget(new ConversationProperties())
                .select(actual, "Q", List.of(), List.of(citation)))
                .isInstanceOf(InputBudgetExceededException.class);
        verifyNoInteractions(transport);
    }
}
