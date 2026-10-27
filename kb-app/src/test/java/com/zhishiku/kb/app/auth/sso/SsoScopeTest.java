package com.zhishiku.kb.app.auth.sso;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsoScopeTest {
    @Test
    void separatesEveryIdentityDimensionAndCase() {
        String initial = SsoScope.provider("feishu", "corp", "app", "app-open-id");
        assertThat(initial).hasSize(78);
        assertThat(initial).isNotEqualTo(SsoScope.provider("feishu", "other", "app", "app-open-id"))
                .isNotEqualTo(SsoScope.provider("feishu", "corp", "other", "app-open-id"))
                .isNotEqualTo(SsoScope.provider("feishu", "corp", "app", "other"))
                .isNotEqualTo(SsoScope.provider("feishu", "Corp", "app", "app-open-id"));
        assertThat(SsoCrypto.digest("ab", "c")).isNotEqualTo(SsoCrypto.digest("a", "bc"));
        assertThat(new VerifiedExternalIdentity("feishu", "corp", "app", "app-open-id", "A").subject()).isEqualTo("A");
    }

    @Test
    void secretAndCallbackRotationOnlyInvalidatesFlows() {
        var p = SsoSettingsTest.configured("wecom");
        var settings = new SsoSettings(p, new MockEnvironment());
        var c = settings.connection("wecom");
        String provider = SsoScope.provider("wecom", c);
        String fingerprint = SsoScope.fingerprint("wecom", settings);
        c.setClientSecret("rotated");
        assertThat(SsoScope.provider("wecom", c)).isEqualTo(provider);
        assertThat(SsoScope.fingerprint("wecom", settings)).isNotEqualTo(fingerprint);
        c.setAgentId("10002");
        assertThat(SsoScope.provider("wecom", c)).isNotEqualTo(provider);
    }

    @Test
    void rejectsInvalidSubjectsWithoutTruncation() {
        for (String value : new String[]{"", " A", "A\n", "a b", "a".repeat(513)})
            assertThatThrownBy(() -> new VerifiedExternalIdentity("feishu", "corp", "app", "app-open-id", value))
                    .isInstanceOf(SsoException.class);
        assertThat(new VerifiedExternalIdentity("feishu", "corp", "app", "app-open-id", "A".repeat(512)).subject())
                .hasSize(512);
    }
}
