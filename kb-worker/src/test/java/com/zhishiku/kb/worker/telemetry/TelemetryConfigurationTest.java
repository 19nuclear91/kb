package com.zhishiku.kb.worker.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.autoconfigure.tracing.TracingProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryConfigurationTest {
    private static final String PREFIX = "kb.telemetry.tracing.";

    @Test
    void defaultsDisableTracingAndBaggageAndEnabledSettingsBindToBoot() {
        var environment = new MockEnvironment();
        apply(environment);
        assertThat(environment.getProperty("management.tracing.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("management.otlp.tracing.endpoint")).isNull();
        var enabled = enabled().withProperty(PREFIX + "sampling-probability", "0.25");
        apply(enabled);
        var properties = Binder.get(enabled).bind("management.tracing", TracingProperties.class).get();
        assertThat(enabled.getProperty("management.tracing.enabled", Boolean.class)).isTrue();
        assertThat(properties.getSampling().getProbability()).isEqualTo(0.25f);
        assertThat(properties.getBaggage().isEnabled()).isFalse();
        assertThat(properties.getBaggage().getRemoteFields()).isEmpty();
        assertThat(enabled.getProperty("management.otlp.tracing.endpoint")).isEqualTo("https://collector.invalid/v1/traces");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not a URI", "https://secret@collector.invalid/v1/traces",
            "https://collector.invalid/v1/traces?secret=token", "https://collector.invalid/#secret", "file:///secret"})
    void rejectsMissingOrUnsafeEndpointsWithoutLeakingValues(String endpoint) {
        assertThatThrownBy(() -> apply(enabled().withProperty(PREFIX + "otlp-endpoint", endpoint)))
                .hasMessage("配置无效：" + PREFIX + "otlp-endpoint").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.1", "1.1", "NaN", "Infinity", "secret"})
    void rejectsInvalidSampling(String probability) {
        assertThatThrownBy(() -> apply(enabled().withProperty(PREFIX + "sampling-probability", probability)))
                .hasMessage("配置无效：" + PREFIX + "sampling-probability").hasNoCause();
    }

    @Test
    void rejectsBaggageAndNativeOverridesAndInvalidTypes() {
        for (String key : new String[]{"management.tracing.baggage.remote-fields",
                "management.tracing.baggage.remote-fields[0]"}) {
            assertThatThrownBy(() -> apply(enabled().withProperty(key, "secret")))
                    .hasMessage("配置无效：management.tracing.baggage.remote-fields").hasNoCause();
        }
        assertThatThrownBy(() -> apply(enabled().withProperty("management.tracing.baggage.enabled", "true")))
                .hasMessage("配置无效：management.tracing.baggage.enabled").hasNoCause();
        assertThatThrownBy(() -> apply(new MockEnvironment().withProperty("management.tracing.enabled", "true")))
                .hasMessage("配置无效：management.tracing.enabled").hasNoCause();
        assertThatThrownBy(() -> apply(enabled().withProperty(PREFIX + "enabled", "secret")))
                .hasMessage("配置无效：" + PREFIX + "enabled").hasNoCause();
    }

    private static MockEnvironment enabled() {
        return new MockEnvironment().withProperty(PREFIX + "enabled", "true")
                .withProperty(PREFIX + "otlp-endpoint", "https://collector.invalid/v1/traces");
    }

    private static void apply(MockEnvironment environment) {
        new TelemetryEnvironmentPostProcessor().postProcessEnvironment(environment, null);
    }
}
