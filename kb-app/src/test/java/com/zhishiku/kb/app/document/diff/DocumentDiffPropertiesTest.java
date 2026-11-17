package com.zhishiku.kb.app.document.diff;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentDiffPropertiesTest {
    @Test
    void defaultsAndOverridesBindOffline() {
        assertThat(bind(Map.of())).isEqualTo(new DocumentDiffProperties(500000, 10000, 5000, 4,
                20000, 250000, 0.3));
        assertThat(bind(Map.of("kb.document-diff.max-concurrent", "2")).maxConcurrent()).isEqualTo(2);
        assertThat(bind(Map.of("kb.document-diff.min-similarity", "0")).minSimilarity()).isZero();
        assertThat(bind(Map.of("kb.document-diff.min-similarity", "1")).minSimilarity()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveLimitsAndInvalidSimilarity() {
        for (String key : List.of("max-chars-per-version", "max-blocks-per-version", "timeout-ms",
                "max-concurrent", "inline-diff-max-chars", "pairing-max-cells")) {
            for (String value : List.of("0", "-1")) {
                assertThatThrownBy(() -> bind(Map.of("kb.document-diff." + key, value)))
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
            }
        }
        for (String value : List.of("-0.1", "1.1", "NaN", "Infinity")) {
            assertThatThrownBy(() -> bind(Map.of("kb.document-diff.min-similarity", value)))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void zeroCapacityPreventsStartup() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("kb.document-diff.max-concurrent=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private static DocumentDiffProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("kb.document-diff", Bindable.of(DocumentDiffProperties.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentDiffProperties.class)
    static class PropertiesConfiguration {
    }
}
