package com.zhishiku.kb.common.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationSettingsTest {
    private ConversationSettings valid() {
        var settings = new ConversationSettings();
        settings.setEnabled(true);
        settings.setSecret("0123456789abcdef0123456789abcdef");
        return settings;
    }

    @Test
    void disabledKeepsLegacySettingsAndEnabledRequiresSecret() {
        assertThatCode(() -> new ConversationSettings().validate(true)).doesNotThrowAnyException();
        var settings = new ConversationSettings();
        settings.setEnabled(true);
        assertThatThrownBy(() -> settings.validate(true)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> valid().validate(true)).doesNotThrowAnyException();
        settings.setSecret("dev-secret-012345678901234567890123456789");
        assertThatThrownBy(() -> settings.validate(true)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> settings.validate(false)).doesNotThrowAnyException();
    }

    @Test
    void rejectsExpiredDedupShortLeaseAndImpossibleBudgets() {
        var settings = valid();
        settings.setRenewalMs(120000);
        assertThatThrownBy(() -> settings.validate(true)).isInstanceOf(IllegalArgumentException.class);
        var nonce = valid();
        nonce.setNonceTtlSeconds(60);
        assertThatThrownBy(() -> nonce.validate(true)).isInstanceOf(IllegalArgumentException.class);
        var budget = valid();
        budget.setMaxOutputTokens(12000);
        assertThatThrownBy(() -> budget.validate(true)).isInstanceOf(IllegalArgumentException.class);
        var retry = valid();
        retry.setSettlementRetrySeconds(86400);
        assertThatThrownBy(() -> retry.validate(true)).isInstanceOf(IllegalArgumentException.class);
    }
}

