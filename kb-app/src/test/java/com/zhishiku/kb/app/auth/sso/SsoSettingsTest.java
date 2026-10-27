package com.zhishiku.kb.app.auth.sso;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsoSettingsTest {
    static SsoProperties configured(String id) {
        var p = new SsoProperties();
        p.setPublicOrigin("https://kb.example.com");
        p.setResultPageUri("https://kb.example.com/auth/result");
        var c = p.getConnections().get(id);
        c.setEnabled(true);
        c.setClientId("app");
        c.setClientSecret("secret");
        c.setTargetEnterpriseId("corp");
        c.setAgentId("10001");
        c.setCallbackUri("https://kb.example.com/api/auth/sso/" + id + "/callback");
        return p;
    }

    @Test
    void defaultsRequireNoProviderAndExposeNoConnections() {
        assertThat(new SsoSettings(new SsoProperties(), new MockEnvironment()).enabled()).isEmpty();
    }

    @Test
    void enablesOnlyConfiguredProviderAndChecksExactOrigin() {
        var s = new SsoSettings(configured("dingtalk"), new MockEnvironment());
        assertThat(s.enabled()).containsExactly("dingtalk");
        assertThatThrownBy(() -> s.connection("feishu")).isInstanceOf(SsoException.class);
        assertThatThrownBy(() -> s.origin("https://evil.example.com")).isInstanceOf(SsoException.class);
    }

    @Test
    void rejectsMissingEnterpriseWildcardAndCrossOrigin() {
        var p = configured("dingtalk");
        p.getConnections().get("dingtalk").setTargetEnterpriseId("");
        assertThatThrownBy(() -> new SsoSettings(p, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
        p.getConnections().get("dingtalk").setTargetEnterpriseId("corp");
        p.setPublicOrigin("*");
        assertThatThrownBy(() -> new SsoSettings(p, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
        p.setPublicOrigin("https://kb.example.com");
        p.setResultPageUri("https://evil.example.com/result");
        assertThatThrownBy(() -> new SsoSettings(p, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownProviderAndProductionHttp() {
        var p = configured("wecom");
        p.getConnections().put("other", new SsoProperties.Connection());
        assertThatThrownBy(() -> new SsoSettings(p, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
        p.getConnections().remove("other");
        p.setPublicOrigin("http://localhost:8080");
        assertThatThrownBy(() -> new SsoSettings(p, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsExplicitDevelopmentLoopbackOnly() {
        var p = configured("feishu");
        p.setPublicOrigin("http://localhost:8080");
        p.setResultPageUri("http://localhost:8080/auth/result");
        p.getConnections().get("feishu").setCallbackUri("http://localhost:8080/api/auth/sso/feishu/callback");
        var env = new MockEnvironment();
        env.setActiveProfiles("dev");
        assertThat(new SsoSettings(p, env).secureCookies()).isFalse();
    }
}
