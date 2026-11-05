package com.zhishiku.kb.app.document.preview;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficePreviewSettingsTest {
    @Test
    void enabledSettingsRequireIsolatedOriginsIndependentSecretsAndFiniteLimits() {
        var settings = valid();
        settings.validate();
        settings.setPreviewOrigin(settings.getApplicationOrigin());
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class);
        settings = valid();
        settings.setOutboxSecret(settings.getConfigurationSecret());
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class);
        settings = valid();
        settings.setAbsoluteSeconds(Integer.MAX_VALUE);
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class);
        settings = valid();
        settings.setSourceBaseUrl("http://user:password@app.invalid/");
        assertThatThrownBy(settings::validate).hasMessage("Office 预览启用配置无效");
        settings = valid();
        settings.setProtocol("unrecognized");
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void earlyFailuresHaveNoStoreAndOnlySafeMessages() throws Exception {
        for (int status : new int[]{400, 401, 403, 404, 415, 429, 503}) {
            var response = new OfficePreviewAdvice().failure(new OfficePreviewFailure(status));
            assertThat(response.getStatusCode().value()).isEqualTo(status);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        }
        var request = new MockHttpServletRequest("GET", "/api/documents/8/versions/9/preview-info");
        var response = new MockHttpServletResponse();
        new OfficeResponseHeaders().doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(401));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    static OfficePreviewSettings valid() {
        var settings = new OfficePreviewSettings();
        settings.setEnabled(true);
        settings.setApplicationOrigin("https://kb.example.invalid");
        settings.setPreviewOrigin("https://preview.example.invalid");
        settings.setSourceBaseUrl("http://kb-app:8081");
        settings.setConfigurationSecret("a".repeat(32));
        settings.setOutboxSecret("b".repeat(32));
        settings.setGatewaySecret("c".repeat(32));
        return settings;
    }
}
