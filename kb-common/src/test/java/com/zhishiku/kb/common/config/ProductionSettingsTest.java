package com.zhishiku.kb.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class ProductionSettingsTest {
    private Map<String, String> safeApp() {
        var settings = new HashMap<>(Map.of("spring.datasource.url", "jdbc:postgresql://db/kb",
                "spring.datasource.username", "service", "spring.datasource.password", "external-secret",
                "spring.data.redis.host", "redis", "kb.storage.endpoint", "https://s3.example",
                "kb.storage.access-key", "external-id", "kb.storage.secret-key", "external-secret",
                "kb.storage.bucket", "documents", "spring.flyway.locations", "classpath:db/migration"));
        management(settings);
        return settings;
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
                Map.entry("sa-token.is-log", "true"),
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

    @ParameterizedTest
    @ValueSource(strings = {"prod", "test", "staging", "default"})
    void everyNonDevProfileUsesProductionRules(String profile) {
        ProductionSettings.validate("kb-app", Set.of(profile), safeApp());
        var settings = safeApp();
        settings.put("kb.dev-auth.enabled", "true");
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of(profile), settings))
                .hasMessageContaining("kb.dev-auth.enabled");
    }

    @ParameterizedTest
    @CsvSource({"com.zhishiku.kb.app.search.service,DEBUG", "com.zhishiku.kb.app.storage.service,TRACE",
            "org.springframework.web,DEBUG", "com.zhishiku.kb.app.mapperhelper,TRACE"})
    void productionAllowsOrdinaryDiagnosticLogging(String logger, String level) {
        var settings = safeApp();
        settings.put("logging.level." + logger, level);
        ProductionSettings.validate("kb-app", Set.of("prod"), settings);
    }

    @ParameterizedTest
    @CsvSource({"com.zhishiku.kb.app.search.mapper,DEBUG", "com.zhishiku.kb.app.search.mapper.SearchMapper,TRACE",
            "com.zhishiku.kb.app.search.mapper.SearchMapper.select,DEBUG", "com.zhishiku.kb.app.auth,TRACE",
            "com.zhishiku.kb,DEBUG", "root,TRACE", "org.springframework.jdbc.core,DEBUG",
            "org.springframework.jdbc.core.StatementCreatorUtils,TRACE", "org.apache.ibatis,DEBUG",
            "cn.dev33.satoken,TRACE", "com.zhishiku.kb.app.search.mapper,ALL"})
    void productionRejectsSensitiveLoggersAndInheritedVerboseLevels(String logger, String level) {
        var settings = safeApp();
        String key = "logging.level." + logger;
        settings.put(key, level);
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessageContaining(key).hasMessageNotContaining("external-secret");
    }

    @Test
    void explicitSafeLevelsProtectSensitiveLoggersFromRootDebug() {
        var settings = safeApp();
        settings.put("logging.level.root", "DEBUG");
        settings.put("logging.level.com.zhishiku.kb", "INFO");
        settings.put("logging.level.cn.dev33.satoken", "OFF");
        settings.put("logging.level.org.apache.ibatis", "WARN");
        settings.put("logging.level.org.springframework.jdbc.core", "INFO");
        ProductionSettings.validate("kb-app", Set.of("prod"), settings);
    }

    @Test
    void safeMapperLevelAllowsDebuggingItsParentFeature() {
        var settings = safeApp();
        settings.put("logging.level.com.zhishiku.kb.app.search", "DEBUG");
        settings.put("logging.level.com.zhishiku.kb.app.search.mapper", "OFF");
        ProductionSettings.validate("kb-app", Set.of("prod"), settings);
        settings.put("logging.level.com.zhishiku.kb.app.search.mapper.SearchMapper", "TRACE");
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessageContaining("logging.level.com.zhishiku.kb.app.search.mapper.SearchMapper");
    }

    @ParameterizedTest
    @CsvSource({"com.zhishiku.kb.app.knowledgegap,DEBUG", "com.zhishiku.kb.app.knowledgegap.mapper,TRACE",
            "com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper,ALL",
            "com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper.insertEntry,DEBUG"})
    void knowledgeGapSqlAndParentLevelsAreSensitive(String logger, String level) {
        var settings = safeApp();
        String key = "logging.level." + logger;
        settings.put(key, level);
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessage("生产禁止敏感数据调试日志：" + key).hasNoCause()
                .hasMessageNotContaining("external-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"logging.group.gapSql", "logging.group.gapSql[0]"})
    void knowledgeGapSqlGroupsCannotBypassSafeLoggerOverrides(String groupKey) {
        var settings = safeApp();
        settings.put("logging.level.com.zhishiku.kb.app.knowledgegap.mapper", "OFF");
        settings.put("logging.level.gapSql", "DEBUG");
        for (String member : List.of("com.zhishiku.kb.app.knowledgegap",
                "com.zhishiku.kb.app.knowledgegap.mapper",
                "com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper")) {
            settings.put(groupKey, member);
            assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                    .hasMessage("生产禁止敏感数据调试日志：logging.level.gapSql").hasNoCause();
        }
    }

    @Test
    void explicitSafeKnowledgeGapMapperAllowsParentDiagnosticsButNotChildSql() {
        var settings = safeApp();
        settings.put("logging.level.com.zhishiku.kb.app.knowledgegap", "DEBUG");
        settings.put("logging.level.com.zhishiku.kb.app.knowledgegap.mapper", "OFF");
        ProductionSettings.validate("kb-app", Set.of("prod"), settings);
        settings.put("logging.level.com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper", "TRACE");
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessageContaining("logging.level.com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper");
    }

    @Test
    void workerMapperLoggingIsAlsoProtected() {
        var settings = safeApp();
        settings.put("logging.level.com.zhishiku.kb.worker.parse", "DEBUG");
        assertThatThrownBy(() -> ProductionSettings.validate("kb-worker", Set.of("prod"), settings))
                .hasMessageContaining("logging.level.com.zhishiku.kb.worker.parse");
        settings.put("logging.level.com.zhishiku.kb.worker.parse.mapper", "INFO");
        ProductionSettings.validate("kb-worker", Set.of("prod"), settings);
    }

    @Test
    void builtInSqlGroupCannotEnableSensitiveLogging() {
        var settings = safeApp();
        settings.put("logging.level.sql", "DEBUG");
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessageContaining("logging.level.sql");
    }

    @ParameterizedTest
    @ValueSource(strings = {"logging.group.diagnostics", "logging.group.diagnostics[0]"})
    void customLogGroupsCannotEnableSensitiveLoggersOrTheirParents(String groupKey) {
        var settings = safeApp();
        settings.put("logging.level.diagnostics", "TRACE");
        settings.put(groupKey, "com.zhishiku.kb.app.search.service,org.springframework.web");
        ProductionSettings.validate("kb-app", Set.of("prod"), settings);
        for (String logger : List.of("com.zhishiku.kb.app.search.mapper.SearchMapper", "com.zhishiku.kb.app.search",
                "org.springframework.jdbc.core", "cn.dev33.satoken")) {
            settings.put(groupKey, logger);
            assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                    .hasMessageContaining("logging.level.diagnostics");
        }
    }

    @Test
    void corsRequiresExactOrigins() {
        var config = new HashMap<String, String>();
        management(config);
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

    @ParameterizedTest
    @CsvSource(value = {"management.server.port|", "management.server.port|0", "management.server.port|-1",
            "management.server.port|65536", "management.server.port|secret", "management.server.port|8081",
            "management.server.address|", "management.server.address|0.0.0.0", "management.server.address|::",
            "management.endpoints.web.exposure.include|health,*"}, delimiter = '|', nullValues = "")
    void rejectsUnsafeManagementSettingsAndDevBypassesProductionRules(String key, String value) {
        var settings = safeApp();
        settings.put(key, value);
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessageContaining(key).hasNoCause().hasMessageNotContaining("secret");
        ProductionSettings.validate("kb-app", Set.of("dev"), settings);
    }

    @ParameterizedTest
    @CsvSource({"08082,8082", "+8082,08082", "8082,+8082", "' 8082 ',8082"})
    void rejectsNumericallyEquivalentBusinessAndManagementPorts(String businessPort, String managementPort) {
        var settings = safeApp();
        settings.put("server.port", businessPort);
        settings.put("management.server.port", managementPort);
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessage("management.server.port").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.0.0", "0.0.0.0", "::", "[::]", "0:0::0", "[0:0::0]",
            "0:0:0:0:0:0:0:0", "::ffff:0.0.0.0", "*"})
    void rejectsEquivalentWildcardAddresses(String address) {
        var settings = safeApp();
        settings.put("management.server.address", address);
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessage("management.server.address").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.1", "::1", "[::1]", "::ffff:127.0.0.1", "2001:db8::1"})
    void acceptsExplicitNonWildcardAddresses(String address) {
        var settings = safeApp();
        settings.put("management.server.address", address);
        ProductionSettings.validate("kb-app", Set.of("prod"), settings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "http://collector.example/v1/traces", "https://secret@collector/v1/traces",
            "https://collector/v1/traces?secret", "https://collector/#secret"})
    void rejectsInvalidProductionTracingEndpoints(String endpoint) {
        var settings = safeApp();
        settings.put("kb.telemetry.tracing.enabled", "true");
        settings.put("kb.telemetry.tracing.otlp-endpoint", endpoint);
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessageContaining("otlp").hasNoCause().hasMessageNotContaining("secret")
                .hasMessageNotContaining("http");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://collector.example/v1/traces", "http://collector:4318/v1/traces",
            "http://10.1.2.3:4318/v1/traces", "http://172.16.0.1:4318/v1/traces", "http://192.168.1.2:4318/v1/traces"})
    void acceptsValidProductionTracingAndBuckets(String endpoint) {
        var settings = safeApp();
        settings.put("kb.telemetry.tracing.enabled", "true");
        settings.put("kb.telemetry.tracing.otlp-endpoint", endpoint);
        settings.put("kb.telemetry.tracing.sampling-probability", "0.25");
        settings.put("kb.telemetry.histograms.search", "1.5s,2s,3s,8s");
        ProductionSettings.validate("kb-app", Set.of("prod"), settings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0,1", "-1,1", "2,1", "1,1", "NaN", "Infinity", "1,secret"})
    void rejectsInvalidBucketsWithoutValues(String buckets) {
        var settings = safeApp();
        String key = "kb.telemetry.histograms.search";
        settings.put(key, buckets);
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessage(key).hasNoCause();
    }

    @Test
    void indexedBucketsAndSamplingAreAlsoValidated() {
        var settings = safeApp();
        String key = "kb.telemetry.histograms.search";
        settings.put(key + "[0]", "2s");
        settings.put(key + "[1]", "1s");
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings)).hasMessage(key);
        settings.remove(key + "[1]");
        settings.put("kb.telemetry.tracing.enabled", "true");
        settings.put("kb.telemetry.tracing.otlp-endpoint", "https://collector/v1/traces");
        settings.put("kb.telemetry.tracing.sampling-probability", "0");
        assertThatThrownBy(() -> ProductionSettings.validate("kb-app", Set.of("prod"), settings))
                .hasMessage("kb.telemetry.tracing.sampling-probability").hasNoCause();
        assertThat(ProductionSettings.histogramSeconds(key, "100ms,1.5s,2,1m,PT2M,1h"))
                .containsExactly(0.1, 1.5, 2, 60, 120, 3600);
    }

    private static void management(Map<String, String> settings) {
        settings.put("management.server.port", "9081");
        settings.put("management.server.address", "127.0.0.1");
        settings.put("management.endpoints.web.exposure.include", "health,info,prometheus");
    }
}
