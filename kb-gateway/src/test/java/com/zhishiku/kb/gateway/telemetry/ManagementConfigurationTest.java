package com.zhishiku.kb.gateway.telemetry;

import com.zhishiku.kb.gateway.config.StartupEnvironmentCheck;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagementConfigurationTest {
    @Test
    void devUsesBusinessPortWhenManagementSettingsAreEmpty() {
        runner("dev").run(context -> {
            new StartupEnvironmentCheck().postProcessEnvironment(context.getEnvironment(), null);
            var properties = Binder.get(context.getEnvironment()).bind("management.server", ManagementServerProperties.class)
                    .orElseGet(ManagementServerProperties::new);
            assertThat(properties.getPort()).isNull();
            assertThat(properties.getAddress()).isNull();
            assertThat(ManagementPortType.get(context.getEnvironment())).isEqualTo(ManagementPortType.SAME);
        });
    }

    @Test
    void productionRequiresSeparateExplicitPortAndAddress() {
        runner("prod").run(context -> assertThatThrownBy(() -> new StartupEnvironmentCheck()
                .postProcessEnvironment(context.getEnvironment(), null)).hasMessageContaining("management.server.port"));
        runner("prod").withPropertyValues("KB_MANAGEMENT_PORT=9080").run(context -> assertThatThrownBy(
                () -> new StartupEnvironmentCheck().postProcessEnvironment(context.getEnvironment(), null))
                .hasMessageContaining("management.server.address"));
        runner("prod").withPropertyValues("KB_MANAGEMENT_PORT=9080", "KB_MANAGEMENT_ADDRESS=127.0.0.1")
                .run(context -> {
                    new StartupEnvironmentCheck().postProcessEnvironment(context.getEnvironment(), null);
                    var properties = Binder.get(context.getEnvironment()).bind("management.server", ManagementServerProperties.class).get();
                    assertThat(properties.getPort()).isEqualTo(9080);
                    assertThat(properties.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
                    assertThat(ManagementPortType.get(context.getEnvironment())).isEqualTo(ManagementPortType.DIFFERENT);
                });
    }

    private static ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile, "KB_APP_BASE_URL=http://app:8081",
                        "KB_AI_BASE_URL=http://ai:8082", "KB_REDIS_HOST=redis", "KB_CORS_ALLOWED_ORIGINS=https://kb.example.com",
                        "KB_MANAGEMENT_PORT=", "KB_MANAGEMENT_ADDRESS=");
    }
}
