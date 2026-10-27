package com.zhishiku.kb.worker.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParsePropertiesTest {

    @Test
    void retryDelayUsesExponentialBackoffAndCap() {
        ParseProperties props = new ParseProperties(300, 1, 2_000, 60_000,
                900, 30_000, 120_000, 0.20, 30_000);

        assertThat(props.retryDelayMillis(1, 0)).isEqualTo(30_000);
        assertThat(props.retryDelayMillis(2, 0)).isEqualTo(60_000);
        assertThat(props.retryDelayMillis(3, 0)).isEqualTo(120_000);
        assertThat(props.retryDelayMillis(8, 0)).isEqualTo(120_000);
        assertThat(props.retryDelayMillis(1, -1)).isEqualTo(24_000);
        assertThat(props.retryDelayMillis(1, 1)).isEqualTo(36_000);
    }

    @Test
    void retryConfigurationRejectsInvalidBounds() {
        assertThatThrownBy(() -> new ParseProperties(300, 1, 2_000, 60_000,
                900, 0, 120_000, 0.2, 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseProperties(300, 1, 2_000, 60_000,
                900, 30_000, 29_999, 0.2, 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseProperties(300, 1, 2_000, 60_000,
                900, 30_000, 120_000, 1.01, 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseProperties(300, 1, 2_000, 60_000,
                900, 30_000, 120_000, 0.2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compatibilityConstructorUsesSafeDefaults() {
        ParseProperties props = new ParseProperties(300, 1, 2_000, 60_000);

        assertThat(props.retryBaseDelayMs()).isEqualTo(ParseProperties.DEFAULT_RETRY_BASE_DELAY_MS);
        assertThat(props.retryMaxDelayMs()).isEqualTo(ParseProperties.DEFAULT_RETRY_MAX_DELAY_MS);
        assertThat(props.retryJitterRatio()).isEqualTo(ParseProperties.DEFAULT_RETRY_JITTER_RATIO);
    }
}
