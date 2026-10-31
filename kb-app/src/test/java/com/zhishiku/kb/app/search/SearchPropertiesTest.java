package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchPropertiesTest {
    @Test
    void bindsDefaultsAndIndependentOverridesWithoutAnApplication() {
        Binder empty = new Binder(new MapConfigurationPropertySource(Map.of()));
        assertThat(empty.bindOrCreate("kb.search.execution", Bindable.of(SearchExecutionProperties.class)))
                .isEqualTo(SearchExecutionProperties.defaults());
        assertThat(empty.bindOrCreate("kb.search.limits", Bindable.of(SearchLimitsProperties.class)))
                .isEqualTo(SearchLimitsProperties.defaults());
        Binder custom = new Binder(new MapConfigurationPropertySource(Map.of(
                "kb.search.execution.rerank-timeout-ms", "1200",
                "kb.search.execution.max-concurrent", "4",
                "kb.search.limits.max-query-codepoints", "100")));
        var execution = custom.bindOrCreate("kb.search.execution", Bindable.of(SearchExecutionProperties.class));
        assertThat(execution.rerankTimeoutMs()).isEqualTo(1200);
        assertThat(execution.maxConcurrent()).isEqualTo(4);
        assertThat(execution.totalTimeoutMs()).isEqualTo(8000);
        assertThat(custom.bindOrCreate("kb.search.limits", Bindable.of(SearchLimitsProperties.class))
                .maxQueryCodepoints()).isEqualTo(100);
    }

    @Test
    void rejectsInvalidTimeRelationshipsCapacityAndOverflow() {
        for (Map<String, String> invalid : List.of(
                Map.of("total-timeout-ms", "0"), Map.of("embedding-timeout-ms", "-1"),
                Map.of("response-reserve-ms", "8000"), Map.of("rerank-timeout-ms", "7501"),
                Map.of("max-concurrent", "1"), Map.of("max-concurrent-per-user", "0"),
                Map.of("retry-after-seconds", "0"), Map.of("total-timeout-ms", Long.toString(Long.MAX_VALUE)))) {
            var values = new HashMap<String, String>();
            invalid.forEach((key, value) -> values.put("kb.search.execution." + key, value));
            Binder binder = new Binder(new MapConfigurationPropertySource(values));
            assertThatThrownBy(() -> binder.bindOrCreate("kb.search.execution",
                    Bindable.of(SearchExecutionProperties.class))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsExplicitZeroAndUnsafeByteLimitsInsteadOfDefaulting() {
        for (String value : List.of("0", "-1", Integer.toString(Integer.MAX_VALUE))) {
            Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                    "kb.search.limits.max-request-bytes", value)));
            assertThatThrownBy(() -> binder.bindOrCreate("kb.search.limits",
                    Bindable.of(SearchLimitsProperties.class))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
