package com.zhishiku.kb.app.auth.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DingTalkLifecycleSettingsTest {
    static DingTalkLifecycleSettings configured(boolean enabled) {
        return new DingTalkLifecycleSettings(enabled, "ding-corp", "oauth-client", "credential-secret", "unified-application",
                "internal", "INTERNAL", 1048576, 1000, 2, 16, 2000, Set.of("wss-open-connection.dingtalk.com"));
    }

    @Test
    void defaultDisabledIsIndependentOfSsoAndContainsNoCredentials() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("kb.auth.sso.enabled", "true")));
        var settings = binder.bindOrCreate("kb.identity.lifecycle.dingtalk", Bindable.of(DingTalkLifecycleSettings.class));
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.maxEmployees()).isEqualTo(1000);
        assertThat(settings.timeoutMillis()).isEqualTo(2000);
        assertThatThrownBy(settings::requireConfigured).hasMessageNotContaining("secret");
        assertThat(configured(true).toString()).doesNotContain("credential", "ding-corp", "oauth-client");
    }

    @Test
    void rejectsMissingSecretsPartialIdentityWrongScopeAndOutOfBounds() {
        for (var values : java.util.List.of(Map.of("enabled", "true"), Map.of("client-id", "client"),
                Map.of("application-type", "THIRD_PARTY"), Map.of("max-employees", "1001"),
                Map.of("workers", "0"), Map.of("queue-capacity", "65"), Map.of("timeout-millis", "2001"),
                Map.of("max-message-bytes", "1048577"), Map.of("stream-hosts", "evil.example"))) {
            var source = new MapConfigurationPropertySource();
            values.forEach((key, value) -> source.put("kb.identity.lifecycle.dingtalk." + key, value));
            assertThatThrownBy(() -> new Binder(source).bindOrCreate("kb.identity.lifecycle.dingtalk",
                    Bindable.of(DingTalkLifecycleSettings.class))).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void validatesExactWssHostWithoutTicketRedirectOrUserInfo() {
        var settings = configured(true);
        assertThat(settings.requireStreamEndpoint("wss://wss-open-connection.dingtalk.com:443").getPort()).isEqualTo(443);
        for (String endpoint : java.util.List.of("ws://wss-open-connection.dingtalk.com", "wss://evil.example",
                "wss://wss-open-connection.dingtalk.com.evil.example", "wss://wss-open-connection.dingtalk.com:80",
                "wss://user@wss-open-connection.dingtalk.com", "wss://wss-open-connection.dingtalk.com?ticket=x")) {
            assertThatThrownBy(() -> settings.requireStreamEndpoint(endpoint)).isInstanceOf(RuntimeException.class);
        }
    }
}
