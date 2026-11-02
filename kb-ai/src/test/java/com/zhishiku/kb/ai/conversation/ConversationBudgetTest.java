package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.common.conversation.SourceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class ConversationBudgetTest {
    final ConversationProperties properties = new ConversationProperties();
    final ConversationBudget budget = new ConversationBudget(properties);

    MultiTurnModel model(long window) {
        return new MultiTurnModel() {
            public boolean supportsTokenUpperBound() {
                return true;
            }

            public long contextWindowTokens() {
                return window;
            }

            public long inputTokenUpperBound(Input input) {
                // 仅确定性离线适配器；生产估算由模型供应商契约负责。
                return input.messages().stream().mapToLong(m -> m.text().codePointCount(0, m.text().length())).sum()
                        + input.materials().stream().mapToLong(c -> c.content().codePointCount(0,
                        c.content().length())).sum() + 100;
            }

            public Flux<Event> generate(Input input) {
                return Flux.empty();
            }
        };
    }

    HistoryTurn history(long seq, String q, String answer, SourceKey key) {
        return new HistoryTurn(UUID.randomUUID(), seq, q, answer, List.of(key));
    }

    RetrievalClient.Citation citation(String text) {
        return new RetrievalClient.Citation(10, "title", 1, 12, text, 11L, 1, 0, null, null, null, null, null, null,
                null, false);
    }

    @Test
    void preservesRolesOriginalQuestionAndTransitiveSourcesAndEvictsWholePairs() {
        var old = history(1, "older", "a".repeat(10000), new SourceKey(1, 2));
        var recent = history(2, "任务租约", "有效答案", new SourceKey(3, 4));
        String question = "它过期后怎么办😀";
        var plan = budget.select(model(12000), question, List.of(recent, old), List.of(citation("材料")));
        assertThat(plan.history()).containsExactly(recent);
        assertThat(plan.input().messages()).containsExactly(new MultiTurnModel.Message("user", "任务租约"),
                new MultiTurnModel.Message("assistant", "有效答案"), new MultiTurnModel.Message("user", question));
        assertThat(plan.input().maxOutputTokens()).isEqualTo(2048);
        assertThat(budget.query(List.of(recent), question)).isEqualTo("任务租约\n" + question);
    }

    @Test
    void followUpSeparatorsFitTheExactCodepointBudgetWithoutTruncatingCurrentQuestion() {
        var first = history(1, "旧", "answer", new SourceKey(1, 2));
        var second = history(2, "😀", "answer", new SourceKey(3, 4));
        String current = "q".repeat(7998);
        String single = budget.query(List.of(first), current);
        assertThat(single).isEqualTo("旧\n" + current);
        assertThat(single.codePointCount(0, single.length())).isEqualTo(8000);

        current = "😀".repeat(7996);
        String both = budget.query(List.of(second, first), current);
        assertThat(both).isEqualTo("旧\n😀\n" + current);
        assertThat(both.codePointCount(0, both.length())).isEqualTo(8000);

        current = "😀".repeat(7997);
        String recentOnly = budget.query(List.of(first, second), current);
        assertThat(recentOnly).isEqualTo("😀\n" + current);
        assertThat(recentOnly.codePointCount(0, recentOnly.length())).isLessThanOrEqualTo(8000);

        current = "😀".repeat(8000);
        assertThat(budget.query(List.of(first, second), current)).isEqualTo(current);
    }

    @Test
    void sourceLimitRemovesHistoryRatherThanDependenciesAndExcludesUnversioned() {
        properties.setMaxSources(1);
        var old = history(1, "q", "answer", new SourceKey(1, 2));
        var unknown = new RetrievalClient.Citation(1, "t", null, 2, "legacy");
        var plan = budget.select(model(12000), "q", List.of(old), List.of(citation("材料"), unknown));
        assertThat(plan.history()).isEmpty();
        assertThat(plan.input().materials()).hasSize(1);
        assertThat(plan.input().materials().getFirst().versionId()).isEqualTo(11);
    }

    @Test
    void truncatesUnicodeMaterialsButRejectsEssentialQuestionWithoutTruncation() {
        var plan = budget.select(model(2200), "原问题", List.of(), List.of(citation("😀".repeat(1000))));
        var material = plan.input().materials().getFirst();
        assertThat(material.contextTruncated()).isTrue();
        assertThat(material.content()).doesNotEndWith("\\uD83D");
        assertThat(plan.input().messages().getLast().text()).isEqualTo("原问题");
        assertThatThrownBy(() -> budget.select(model(2100), "问".repeat(8000), List.of(), List.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThat(budget.query(List.of(), "当前")).isEqualTo("当前");
        assertThat(budget.query(List.of(history(1, "q".repeat(8000), "a", new SourceKey(1, 2))), "当前")).isEqualTo("当前");
    }

    @Test
    void absentOrUnsupportedEstimatorRejectsInsteadOfGeneratingPlaceholder() {
        assertThatThrownBy(() -> budget.ready(null)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        var unsupported = new MultiTurnModel() {
            public boolean supportsTokenUpperBound() {
                return false;
            }

            public long contextWindowTokens() {
                return 12000;
            }

            public long inputTokenUpperBound(Input input) {
                throw new AssertionError();
            }

            public Flux<Event> generate(Input input) {
                throw new AssertionError();
            }
        };
        assertThatThrownBy(() -> budget.ready(unsupported)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void estimatedBoundaryRecountersAfterEveryUnicodeCutAndKeepsOriginalQuestion() {
        for (long window : new long[]{16000, 10000}) {
            var model = mock(MultiTurnModel.class);
            var capability = new BudgetCapability(BudgetKind.ESTIMATED, window, 512);
            when(model.budgetCapability()).thenReturn(capability);
            when(model.countInputTokens(any())).thenAnswer(call -> {
                MultiTurnModel.Input selected = call.getArgument(0);
                long length = selected.materials().getFirst().content().codePointCount(0,
                        selected.materials().getFirst().content().length());
                return new InputTokenCount(BudgetKind.ESTIMATED, (window == 16000 ? 9439 : 7439) + length);
            });
            var plan = budget.select(model, "原问题😀", List.of(), List.of(citation("😀😀")));
            assertThat(plan.input().materials().getFirst().content()).isEqualTo("😀");
            assertThat(plan.input().materials().getFirst().contextTruncated()).isTrue();
            assertThat(plan.input().messages().getLast().text()).isEqualTo("原问题😀");
            assertThat(plan.capability()).isEqualTo(capability);
            verify(model, times(2)).countInputTokens(any());
        }
    }

    @Test
    void estimatedHistoryEvictionRemovesWholePairAndDependencies() {
        var model = mock(MultiTurnModel.class);
        when(model.budgetCapability()).thenReturn(new BudgetCapability(BudgetKind.ESTIMATED, 16000, 512));
        when(model.countInputTokens(any())).thenAnswer(call -> {
            MultiTurnModel.Input selected = call.getArgument(0);
            return new InputTokenCount(BudgetKind.ESTIMATED, selected.messages().size() > 3 ? 9441 : 9440);
        });
        var old = history(1, "old", "old answer", new SourceKey(1, 2));
        var recent = history(2, "recent", "recent answer", new SourceKey(3, 4));
        var plan = budget.select(model, "q", List.of(recent, old), List.of(citation("材料")));
        assertThat(plan.history()).containsExactly(recent);
        assertThat(plan.input().messages()).extracting(MultiTurnModel.Message::text)
                .containsExactly("recent", "recent answer", "q");
        assertThatThrownBy(() -> plan.history().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(model, times(2)).countInputTokens(any());
    }

    @Test
    void materialExhaustionIsNeverAnEmptySuccessPlanInEitherMode() {
        for (var kind : new BudgetKind[]{BudgetKind.VERIFIED, BudgetKind.ESTIMATED}) {
            var model = mock(MultiTurnModel.class);
            when(model.budgetCapability()).thenReturn(new BudgetCapability(kind, 12000, 0));
            when(model.countInputTokens(any())).thenAnswer(call -> {
                MultiTurnModel.Input selected = call.getArgument(0);
                return new InputTokenCount(kind, selected.materials().isEmpty() ? 1 : 12000);
            });
            assertThatThrownBy(() -> budget.select(model, "private-question", List.of(), List.of(citation("x"))))
                    .isInstanceOf(InputBudgetExceededException.class).hasNoCause()
                    .hasMessageNotContaining("private-question");
            verify(model).countInputTokens(any());
        }
    }

    @Test
    void characterGuardCanExhaustMaterialsEvenWhenTokenCountFits() {
        properties.setMaxContextCodepoints(ConversationBudget.INSTRUCTION.codePointCount(0,
                ConversationBudget.INSTRUCTION.length()) + 1);
        assertThatThrownBy(() -> budget.select(model(12000), "q", List.of(), List.of(citation("😀"))))
                .isInstanceOf(InputBudgetExceededException.class);
        assertThat(budget.select(model(12000), "q", List.of(), List.of()).input().materials()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"VERIFIED,false", "ESTIMATED,false", "VERIFIED,true", "ESTIMATED,true"})
    void whitespaceOnlyPrefixesExhaustUsableMaterials(BudgetKind kind, boolean characterGuard) {
        var model = materialLimitedModel(kind, characterGuard, 2);
        var original = citation(" \u3000😀文");
        assertThatThrownBy(() -> budget.select(model, "q", List.of(), List.of(original)))
                .isInstanceOf(InputBudgetExceededException.class).hasNoCause();
        assertThat(original.content()).isEqualTo(" \u3000😀文");
        assertThat(original.contextTruncated()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"VERIFIED,false", "ESTIMATED,false", "VERIFIED,true", "ESTIMATED,true"})
    void discardsBlankPrefixButKeepsOtherUsefulMaterialAndItsIdentity(BudgetKind kind, boolean characterGuard) {
        var model = materialLimitedModel(kind, characterGuard, 5);
        var useful = citation("证据😀");
        var discarded = new RetrievalClient.Citation(20, "other", 2, 22, " \u3000😀文", 21L,
                null, null, null, null, null, null, null, null, null, false);
        var plan = budget.select(model, "q", List.of(), List.of(useful, discarded));
        assertThat(plan.input().materials()).containsExactly(useful);
        assertThat(plan.input().messages()).containsExactly(new MultiTurnModel.Message("user", "q"));
        assertThat(useful.contextTruncated()).isFalse();
        assertThat(discarded.content()).isEqualTo(" \u3000😀文");
    }

    private MultiTurnModel materialLimitedModel(BudgetKind kind, boolean characterGuard, int materialLimit) {
        var model = mock(MultiTurnModel.class);
        when(model.budgetCapability()).thenReturn(new BudgetCapability(kind, 12000,
                kind == BudgetKind.ESTIMATED ? 512 : 0));
        if (characterGuard) {
            properties.setMaxContextCodepoints(ConversationBudget.INSTRUCTION.codePointCount(0,
                    ConversationBudget.INSTRUCTION.length()) + 1 + materialLimit);
            when(model.countInputTokens(any())).thenReturn(new InputTokenCount(kind, 1));
        } else {
            // 固定计数替身让可用输入额度恰好容纳指定数量的材料字符。
            long capacity = kind == BudgetKind.ESTIMATED ? 9440 : 9952;
            when(model.countInputTokens(any())).thenAnswer(call -> {
                MultiTurnModel.Input selected = call.getArgument(0);
                long materialChars = selected.materials().stream()
                        .mapToLong(c -> c.content().codePointCount(0, c.content().length())).sum();
                return new InputTokenCount(kind, capacity - materialLimit + materialChars);
            });
        }
        return model;
    }

    @Test
    void mismatchedCountExceptionsOrChangingCapabilitiesAreUnavailable() {
        var model = mock(MultiTurnModel.class);
        var capability = new BudgetCapability(BudgetKind.ESTIMATED, 12000, 512);
        when(model.budgetCapability()).thenReturn(capability);
        when(model.countInputTokens(any())).thenReturn(new InputTokenCount(BudgetKind.VERIFIED, 1));
        assertUnavailable(model);
        when(model.countInputTokens(any())).thenThrow(new IllegalArgumentException("private-prompt"));
        assertUnavailable(model);
        when(model.budgetCapability()).thenReturn(capability, new BudgetCapability(BudgetKind.ESTIMATED, 16000, 512));
        assertUnavailable(model);
    }

    private void assertUnavailable(MultiTurnModel model) {
        assertThatThrownBy(() -> budget.select(model, "private-question", List.of(), List.of(citation("private-material"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(503))
                .hasNoCause().hasMessageNotContaining("private");
    }
}
