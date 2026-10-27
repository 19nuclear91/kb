package com.zhishiku.kb.common.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSettingsTest {
    private Map<String, String> safeApp() {
        return new HashMap<>(Map.of("spring.datasource.url", "jdbc:postgresql://db/kb",
                "spring.datasource.username", "service", "spring.datasource.password", "external-secret",
                "spring.data.redis.host", "redis", "kb.storage.endpoint", "https://s3.example",
                "kb.storage.access-key", "external-id", "kb.storage.secret-key", "external-secret",
                "kb.storage.bucket", "documents", "spring.flyway.locations", "classpath:db/migration"));
    }

    @Test
    void productionAcceptsExternalSettings() {
        ProductionSettings.validate("kb-app", Set.of("prod"), safeApp());
    }

    @Test
    void productionRejectsUnsafeOverridesAndMissingCredentials() {
        for (var unsafe : List.of(Map.entry("kb.dev-auth.enabled", "true"),
                Map.entry("spring.datasource.password", ""), Map.entry("spring.datasource.password", "kb_dev_pwd"),
                Map.entry("spring.flyway.locations", "classpath:db/migration,classpath:db/dev"),
                Map.entry("spring.flyway.baseline-on-migrate", "true"),
                Map.entry("logging.level.com.zhishiku.kb.app.search.mapper", "DEBUG"))) {
            var config = safeApp();
            config.put(unsafe.getKey(), unsafe.getValue());
            assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("external-secret").hasMessageNotContaining("kb_dev_pwd");
        }
    }

    @Test
    void explicitDevIsAllowedButMixedProfilesAreRejected() {
        ProductionSettings.validate("kb-app", Set.of("dev"), Map.of());
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("dev", "prod"), safeApp()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void corsRequiresExactOrigins() {
        var config = new HashMap<String, String>();
        config.put("spring.data.redis.host", "redis");
        config.put("spring.cloud.gateway.routes[0].uri", "http://app:8081");
        config.put("spring.cloud.gateway.routes[1].uri", "http://ai:8082");
        String origins = "spring.cloud.gateway.globalcors.cors-configurations[/**].allowedOrigins";
        for (String value : List.of("*", "http://localhost:*", "null", "https://example.com/path")) {
            config.put(origins, value);
            assertThatThrownBy(() -> ProductionSettings.validate("kb-gateway", Set.of("prod"), config))
                    .isInstanceOf(IllegalStateException.class);
        }
        config.put(origins, "https://kb.example.com");
        ProductionSettings.validate("kb-gateway", Set.of("prod"), config);
    }
}

