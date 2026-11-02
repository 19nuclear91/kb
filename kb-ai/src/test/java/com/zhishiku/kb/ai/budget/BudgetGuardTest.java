package com.zhishiku.kb.ai.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetGuardTest {
    @Test
    void defaultCapacityIncludesMarginExactlyOnceAndHonorsTheSmallerLimit() {
        var estimated = new BudgetCapability(BudgetKind.ESTIMATED, 16000, 512);
        assertThat(BudgetGuard.inputCapacity(estimated, 12000, 2048)).isEqualTo(9440);
        assertThat(BudgetGuard.fits(estimated, new InputTokenCount(BudgetKind.ESTIMATED, 9440), 12000, 2048)).isTrue();
        assertThat(BudgetGuard.fits(estimated, new InputTokenCount(BudgetKind.ESTIMATED, 9441), 12000, 2048)).isFalse();
        assertThat(BudgetGuard.inputCapacity(new BudgetCapability(BudgetKind.ESTIMATED, 10000, 512), 12000, 2048))
                .isEqualTo(7440);
        assertThat(BudgetGuard.inputCapacity(new BudgetCapability(BudgetKind.VERIFIED, 16000, 0), 12000, 2048))
                .isEqualTo(9952);
    }

    @Test
    void extremeValuesDoNotOverflowOrBecomeZeroCounts() {
        var capability = new BudgetCapability(BudgetKind.ESTIMATED, Long.MAX_VALUE, 0);
        assertThat(BudgetGuard.fits(capability, new InputTokenCount(BudgetKind.ESTIMATED, Long.MAX_VALUE),
                Long.MAX_VALUE, 1)).isFalse();
        assertThat(BudgetGuard.fits(capability, new InputTokenCount(BudgetKind.ESTIMATED, Long.MAX_VALUE - 1),
                Long.MAX_VALUE, 1)).isTrue();
        var margin = new BudgetCapability(BudgetKind.ESTIMATED, 1, Long.MAX_VALUE);
        assertThatThrownBy(() -> BudgetGuard.inputCapacity(margin, 1, Long.MAX_VALUE))
                .hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
    }

    @Test
    void invalidOrMismatchedCapabilitiesCannotPass() {
        var capability = new BudgetCapability(BudgetKind.ESTIMATED, 12000, 512);
        assertThatThrownBy(() -> BudgetGuard.fits(capability, new InputTokenCount(BudgetKind.VERIFIED, 0), 12000, 2048))
                .hasMessage("BUDGET_UNAVAILABLE");
        assertThatThrownBy(() -> BudgetGuard.fits(capability, null, 12000, 2048)).hasMessage("BUDGET_UNAVAILABLE");
        assertThatThrownBy(() -> BudgetGuard.inputCapacity(null, 12000, 2048)).hasMessage("BUDGET_UNAVAILABLE");
        assertThatThrownBy(() -> BudgetGuard.inputCapacity(BudgetCapability.unavailable(), 12000, 2048))
                .hasMessage("BUDGET_UNAVAILABLE");
        for (long invalid : new long[]{0, -1, Long.MIN_VALUE}) {
            assertThatThrownBy(() -> BudgetGuard.inputCapacity(capability, invalid, 2048)).hasMessage("BUDGET_UNAVAILABLE");
            assertThatThrownBy(() -> BudgetGuard.inputCapacity(capability, 12000, invalid)).hasMessage("BUDGET_UNAVAILABLE");
        }
        assertThatThrownBy(() -> BudgetGuard.inputCapacity(capability, 2560, 2048)).hasMessage("BUDGET_UNAVAILABLE");
    }
}
