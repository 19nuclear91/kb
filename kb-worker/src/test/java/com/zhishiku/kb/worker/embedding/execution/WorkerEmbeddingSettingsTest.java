package com.zhishiku.kb.worker.embedding.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerEmbeddingSettingsTest {
    @Test
    void defaultsAndBoundaryValuesAreBound() {
        assertThat(WorkerEmbeddingSettings.from(new MockEnvironment())).isEqualTo(WorkerEmbeddingSettings.defaults());
        var environment = new MockEnvironment()
                .withProperty("kb.worker.embedding.max-request-bytes", "16777216")
                .withProperty("kb.worker.embedding.max-response-bytes", "67108864")
                .withProperty("kb.worker.embedding.max-json-depth", "128")
                .withProperty("kb.worker.embedding.rebuild-generation-timeout-seconds", "3600")
                .withProperty("kb.worker.embedding.probe-timeout-seconds", "60");
        assertThat(WorkerEmbeddingSettings.from(environment))
                .isEqualTo(new WorkerEmbeddingSettings(16777216, 67108864, 128, 3600, 60));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "16777217", "2147483648", "NaN", "secret-value", ""})
    void invalidValuesAreRejectedWithoutEcho(String value) {
        var environment = new MockEnvironment().withProperty("kb.worker.embedding.max-request-bytes", value);
        assertThatThrownBy(() -> WorkerEmbeddingSettings.from(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid kb.worker.embedding limits").hasNoCause();
    }

    @Test
    void numericValuesAliasesAndPlaceholdersCannotTruncateFractions() {
        for (String name : new String[]{"max-request-bytes", "maxRequestBytes"}) {
            var environment = new MockEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("yaml",
                    Map.of("kb.worker.embedding." + name, 1.5)));
            assertThatThrownBy(() -> WorkerEmbeddingSettings.from(environment))
                    .hasMessage("invalid kb.worker.embedding limits").hasNoCause();
        }
        var environment = new MockEnvironment().withProperty("INPUT_LIMIT", "1.5")
                .withProperty("kb.worker.embedding.max-request-bytes", "${INPUT_LIMIT}");
        assertThatThrownBy(() -> WorkerEmbeddingSettings.from(environment)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allFieldsEnforceTheirOwnMaximumAndMinimum() {
        Map<String, Integer> maxima = Map.of("max-response-bytes", 67108864, "max-json-depth", 128,
                "rebuild-generation-timeout-seconds", 3600, "probe-timeout-seconds", 60);
        maxima.forEach((field, maximum) -> {
            for (int value : new int[]{0, maximum + 1}) {
                var environment = new MockEnvironment().withProperty("kb.worker.embedding." + field, String.valueOf(value));
                assertThatThrownBy(() -> WorkerEmbeddingSettings.from(environment)).isInstanceOf(IllegalArgumentException.class);
            }
        });
    }
}
