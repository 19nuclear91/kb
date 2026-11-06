package com.zhishiku.kb.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupEnvironmentCheckTest {
    @Test
    void startupCheckIsRegisteredWithSpringBoot() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(StartupEnvironmentCheck.class.getName())
                .doesNotContain("com.zhishiku.kb.gateway.config.ProductionEnvironmentCheck");
    }

    @Test
    void environmentVariablesParticipateInRequiredAndSensitiveSettingsChecks() {
        var environment = productionEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("externalEnvironment",
                Map.of("SPRING_DATA_REDIS_HOST", "redis", "SA_TOKEN_IS_LOG", "true")));
        assertThatThrownBy(() -> check(environment)).hasMessageContaining("Sa-Token");
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
                Map.of("sa-token.is-log", "false")));
        assertThatCode(() -> check(environment)).doesNotThrowAnyException();
    }

    @Test
    void loggerValidationUsesFinalPrecedenceRegardlessOfEnumeratedKeyOrder() {
        var environment = productionEnvironment().withProperty("spring.data.redis.host", "redis")
                .withProperty("logging.level.org.springframework.jdbc.core", "INFO");
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("externalEnvironment",
                Map.of("LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_JDBC_CORE", "TRACE")));
        assertThatThrownBy(() -> check(environment)).hasMessageContaining("logging.level.org.springframework.jdbc.core");
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
                Map.of("logging.level.org.springframework.jdbc.core", "WARN")));
        assertThatCode(() -> check(environment)).doesNotThrowAnyException();
    }

    @Test
    void environmentOnlyLoggerCanEnableOrdinaryDiagnosticsButCannotEnableSensitiveLogging() {
        var environment = productionEnvironment().withProperty("spring.data.redis.host", "redis");
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("externalEnvironment",
                Map.of("LOGGING_LEVEL_COM_ZHISHIKU_KB_GATEWAY_FILTER", "DEBUG")));
        assertThatCode(() -> check(environment)).doesNotThrowAnyException();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("sensitiveEnvironment",
                Map.of("LOGGING_LEVEL_CN_DEV33_SATOKEN", "TRACE")));
        assertThatThrownBy(() -> check(environment)).hasMessageContaining("logging.level.cn.dev33.satoken");
    }

    @Test
    void environmentDefinedLogGroupsCannotBypassSensitiveLoggingChecks() {
        var environment = productionEnvironment().withProperty("spring.data.redis.host", "redis");
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("externalEnvironment",
                Map.of("LOGGING_GROUP_DIAGNOSTICS", "cn.dev33.satoken", "LOGGING_LEVEL_DIAGNOSTICS", "DEBUG")));
        assertThatThrownBy(() -> check(environment)).hasMessageContaining("logging.level.diagnostics");
    }

    @Test
    void activeProfilesOverrideDefaultsAndOnlyDevBypassesProductionRules() {
        var environment = new MockEnvironment();
        environment.setDefaultProfiles("dev");
        assertThatCode(() -> check(environment)).doesNotThrowAnyException();
        environment.setActiveProfiles("staging");
        assertThatThrownBy(() -> check(environment)).hasMessageContaining("management.server.port");
        environment.setActiveProfiles("dev", "prod");
        assertThatThrownBy(() -> check(environment)).hasMessageContaining("dev 与 prod");
    }

    private static MockEnvironment productionEnvironment() {
        var environment = new MockEnvironment()
                .withProperty("management.server.port", "9080")
                .withProperty("management.server.address", "127.0.0.1")
                .withProperty("spring.cloud.gateway.routes[0].uri", "http://app:8081")
                .withProperty("spring.cloud.gateway.routes[1].uri", "http://ai:8082")
                .withProperty("spring.cloud.gateway.globalcors.cors-configurations[/**].allowedOrigins",
                        "https://kb.example.com");
        environment.setDefaultProfiles("prod");
        return environment;
    }

    private static void check(MockEnvironment environment) {
        new StartupEnvironmentCheck().postProcessEnvironment(environment, null);
    }
}
