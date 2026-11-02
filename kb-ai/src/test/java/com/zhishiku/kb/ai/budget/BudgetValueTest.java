package com.zhishiku.kb.ai.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetValueTest {
    @Test
    void invalidCapabilitiesCannotRepresentAvailableBudgets() {
        assertThatThrownBy(() -> new BudgetCapability(null, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetCapability(BudgetKind.UNAVAILABLE, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetCapability(BudgetKind.UNAVAILABLE, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        for (var kind : new BudgetKind[]{BudgetKind.VERIFIED, BudgetKind.ESTIMATED}) {
            assertThatThrownBy(() -> new BudgetCapability(kind, 0, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new BudgetCapability(kind, -1, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new BudgetCapability(kind, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new BudgetCapability(BudgetKind.VERIFIED, 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsRequireAnAvailableKindAndNonnegativeValue() {
        assertThatThrownBy(() -> new InputTokenCount(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InputTokenCount(BudgetKind.UNAVAILABLE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InputTokenCount(BudgetKind.ESTIMATED, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new InputTokenCount(BudgetKind.VERIFIED, 0).tokens()).isZero();
    }

    @Test
    void snapshotsAreImmutableValues() {
        var capability = new BudgetCapability(BudgetKind.ESTIMATED, 12000, 512);
        var changed = new BudgetCapability(capability.kind(), 16000, 0);
        assertThat(capability).isEqualTo(new BudgetCapability(BudgetKind.ESTIMATED, 12000, 512));
        assertThat(changed).isNotEqualTo(capability);
        assertThat(new InputTokenCount(BudgetKind.ESTIMATED, 18))
                .isEqualTo(new InputTokenCount(BudgetKind.ESTIMATED, 18));
        assertThat(BudgetCapability.unavailable().windowTokens()).isZero();
    }
}
