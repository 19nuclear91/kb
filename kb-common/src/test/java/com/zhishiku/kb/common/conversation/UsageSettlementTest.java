package com.zhishiku.kb.common.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageSettlementTest {
    @Test
    void preservesUnknownNullAndZeroOnlyWhenNotInvokedAndTruthfulOverReservation() {
        var unknown = UsageSettlement.calculate(true, false, 100L, 20L, 12000);
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(unknown.promptTokens()).isNull();
        assertThat(unknown.completionTokens()).isNull();
        assertThat(unknown.chargedTokens()).isEqualTo(12000);
        assertThat(UsageSettlement.calculate(false, false, null, null, 12000)).isEqualTo(new UsageSettlement.Value(
                "NOT_INVOKED", 0L, 0L, 0));
        assertThat(UsageSettlement.calculate(true, true, 14000L, 10L, 12000).chargedTokens()).isEqualTo(14010);
        assertThatThrownBy(() -> UsageSettlement.calculate(true, true, null, 1L, 12000)).isInstanceOf(IllegalArgumentException.class);
    }
}

