package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiTurnBudgetCompatibilityTest {
    @Test
    void originalStrictImplementationsNeedNoNewMethods() {
        var model = new LegacyModel(true);
        assertThat(model.budgetCapability()).isEqualTo(new BudgetCapability(BudgetKind.VERIFIED, 16000, 0));
        assertThat(model.countInputTokens(null)).isEqualTo(new InputTokenCount(BudgetKind.VERIFIED, 23));
        assertThat(model.countCalls).isEqualTo(1);
    }

    @Test
    void unavailableLegacyModelDoesNotReadWindowOrCount() {
        var model = new LegacyModel(false);
        assertThat(model.budgetCapability()).isEqualTo(BudgetCapability.unavailable());
        assertThatThrownBy(() -> model.countInputTokens(null)).hasMessage("模型预算不可用");
        assertThat(model.countCalls).isZero();
        assertThat(model.windowCalls).isZero();
    }

    private static class LegacyModel implements MultiTurnModel {
        private final boolean supported;
        private int countCalls;
        private int windowCalls;

        LegacyModel(boolean supported) {
            this.supported = supported;
        }

        @Override
        public boolean supportsTokenUpperBound() {
            return supported;
        }

        @Override
        public long contextWindowTokens() {
            windowCalls++;
            return 16000;
        }

        @Override
        public long inputTokenUpperBound(Input input) {
            countCalls++;
            return 23;
        }

        @Override
        public Flux<Event> generate(Input input) {
            return Flux.empty();
        }
    }
}
