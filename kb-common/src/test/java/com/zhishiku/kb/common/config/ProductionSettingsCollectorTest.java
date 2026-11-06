package com.zhishiku.kb.common.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSettingsCollectorTest {
    @Test
    void requiredKeysAreResolvedEvenWhenPropertyNamesCannotBeEnumerated() {
        var values = Map.of("spring.datasource.password", "external-secret", "management.server.port", "9081",
                "sa-token.is-log", "false");
        var settings = ProductionSettingsCollector.collect(List.of(), values::get);
        assertThat(settings).containsAllEntriesOf(values);
    }

    @Test
    void collectsDynamicKeysWithoutLosingIndexesOrLoggerClassNames() {
        var values = Map.of("kb.telemetry.histograms.search[0]", "1s",
                "spring.cloud.gateway.globalcors.cors-configurations[/**].allowedOrigins[0]", "https://kb.example.com",
                "logging.level.com.zhishiku.kb.app.search.mapper.SearchMapper", "OFF",
                "management.tracing.enabled", "false", "unrelated.secret", "excluded");
        var settings = ProductionSettingsCollector.collect(values.keySet(), values::get);
        assertThat(settings).containsEntry("kb.telemetry.histograms.search[0]", "1s")
                .containsEntry("spring.cloud.gateway.globalcors.cors-configurations[/**].allowedOrigins[0]",
                        "https://kb.example.com")
                .containsEntry("logging.level.com.zhishiku.kb.app.search.mapper.SearchMapper", "OFF")
                .containsEntry("management.tracing.enabled", "false")
                .doesNotContainKey("unrelated.secret");
    }
}
