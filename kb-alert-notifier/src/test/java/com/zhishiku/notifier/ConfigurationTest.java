package com.zhishiku.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class ConfigurationTest extends TestSupport {
    @Test
    void bindsIndependentConfigurationWithoutBusinessDependenciesOrWebServer() {
        MockEnvironment env = environment();
        env.setProperty("server.port", "80");
        env.setProperty("management.endpoints.web.exposure.include", "*");
        NotifierSettings loaded = NotifierBootstrap.prepare(env);
        assertThat(loaded.port).isEqualTo(8090);
        assertThat(loaded.managementPort).isEqualTo(9090);
        assertThat(loaded.address).isEqualTo("127.0.0.1");
        assertThat(Binder.get(env).bind("server", ServerProperties.class).get().getPort()).isEqualTo(8090);
        assertThat(Binder.get(env).bind("management.server", ManagementServerProperties.class).get().getPort()).isEqualTo(9090);
        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
        new ApplicationContextRunner().withInitializer(context -> context.setEnvironment(env))
                .withBean(NotifierSettings.class, () -> NotifierBootstrap.prepare(env)).run(context -> {
                    assertThat(context).hasSingleBean(NotifierSettings.class);
                    assertThat(context.getBean(NotifierSettings.class)).isSameAs(loaded);
                });
        assertThat(calls).hasValue(0);
    }

    @Test
    void environmentAliasesAreExplicitAndPortBindingsCannotBeCollapsed() {
        MockEnvironment env = new MockEnvironment();
        Map<String, String> values = Map.of("ENVIRONMENT", "prod", "DEPLOYMENT", "kb-main",
                "CATALOG_FILE", directory.resolve("catalog.json").toString(),
                "BEARER_SECRET_FILE", directory.resolve("bearer").toString(),
                "DINGTALK_TOKEN_FILE", directory.resolve("token").toString(),
                "DINGTALK_SECRET_FILE", directory.resolve("signing").toString(),
                "GRAFANA_ROOT", "https://grafana.corp.internal", "ADMIN_ROOT", "https://admin.corp.internal");
        values.forEach((key, value) -> env.setProperty("KB_NOTIFIER_" + key, value));
        env.setProperty("KB_NOTIFIER_PORT", "8190");
        env.setProperty("KB_NOTIFIER_MANAGEMENT_PORT", "9190");
        env.setProperty("KB_NOTIFIER_ADDRESS", "172.28.0.10");
        env.setProperty("KB_NOTIFIER_MANAGEMENT_ADDRESS", "172.28.0.10");
        NotifierSettings result = NotifierBootstrap.prepare(env);
        assertThat(result.port).isEqualTo(8190);
        assertThat(result.managementPort).isEqualTo(9190);
        assertThat(result.address).isEqualTo("172.28.0.10");
        assertThat(result.toString()).doesNotContain(TOKEN, SECRET, BEARER);
    }

    @ParameterizedTest
    @CsvSource({"environment,''", "deployment,Example", "environment,prod/evil", "port,0", "port,65536",
            "port,secret-canary", "management-port,8090", "management-port,-1", "address,0.0.0.0",
            "management-address,::", "management-address,8.8.8.8", "address,public-host-canary",
            "grafana-root,http://grafana.corp.internal", "admin-root,https://user:pass@corp.internal",
            "admin-root,https://corp.internal/?token=private", "admin-root,https://corp.internal/path",
            "admin-root,https://corp.internal/#token", "admin-root,https://example.com",
            "admin-root,https://corp.internal:0", "admin-root,https://corp.internal:65536", "admin-root,https://corp.internal:",
            "address,127.1", "address,2130706433", "address,192.168.1.999", "address,192.168.001.1", "address,::ffff:8.8.8.8"})
    void invalidValuesFailWithConfigurationNameOnly(String field, String value) {
        MockEnvironment env = environment().withProperty("kb.notifier." + field, value);
        assertThatThrownBy(() -> NotifierBootstrap.prepare(env)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid configuration: kb.notifier." + field).hasNoCause();
    }

    @Test
    void missingConfigurationFailsBeforeAnythingCanReceive() {
        assertThatThrownBy(() -> NotifierBootstrap.prepare(new MockEnvironment())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid configuration: kb.notifier.binding").hasNoCause();
    }

    @Test
    void unresolvedEnvironmentPlaceholdersHaveSanitizedBindingErrors() {
        MockEnvironment env = environment().withProperty("KB_NOTIFIER_ENVIRONMENT", "${private-configuration-canary}");
        assertThatThrownBy(() -> NotifierBootstrap.prepare(env)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid configuration: kb.notifier.binding").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "changemechangemechangemechangemechangeme", " secret-with-whitespace-12345678901234567890", "line\nbreak-123456789012345678901234567890"})
    void rejectsInvalidBearerFileWithoutLeakingValue(String value) throws Exception {
        Files.writeString(directory.resolve("bearer"), value);
        assertThatThrownBy(() -> new NotifierSettings(properties())).hasMessage("Invalid configuration: kb.notifier.bearer-secret-file")
                .hasNoCause();
    }

    @Test
    void secretFilesAreBoundedUtf8AndReloadOnlyOnRestart() throws Exception {
        Files.writeString(directory.resolve("bearer"), "密".repeat(11) + "\r\n");
        NotifierSettings fresh = new NotifierSettings(properties());
        assertThat(fresh.authenticates("Bearer " + "密".repeat(11))).isTrue();
        assertThat(settings.authenticates("Bearer " + BEARER)).isTrue();
        Files.write(directory.resolve("bearer"), new byte[]{(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> new NotifierSettings(properties())).hasMessageContaining("bearer-secret-file").hasNoCause();
        Files.writeString(directory.resolve("bearer"), "x".repeat(515));
        assertThatThrownBy(() -> new NotifierSettings(properties())).hasMessageContaining("bearer-secret-file");
        Files.delete(directory.resolve("bearer"));
        assertThatThrownBy(() -> new NotifierSettings(properties())).hasMessageContaining("bearer-secret-file").hasNoCause();
    }

    @Test
    void bearerMinimumAndBomAreValidatedWithoutExposingContent() throws Exception {
        Files.writeString(directory.resolve("bearer"), "a".repeat(31));
        assertThatThrownBy(() -> new NotifierSettings(properties())).hasMessageContaining("bearer-secret-file");
        Files.writeString(directory.resolve("bearer"), "a".repeat(32));
        assertThat(new NotifierSettings(properties()).authenticates("Bearer " + "a".repeat(32))).isTrue();
        Files.writeString(directory.resolve("bearer"), "\ufeff" + BEARER);
        assertThatThrownBy(() -> new NotifierSettings(properties())).hasMessageContaining("bearer-secret-file").hasNoCause();
        Files.delete(directory.resolve("bearer"));
        Files.createDirectory(directory.resolve("bearer"));
        assertThatThrownBy(() -> new NotifierSettings(properties())).hasMessageContaining("bearer-secret-file").hasNoCause();
    }

    @Test
    void managementReadsAreLocalAndPortRestricted() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/actuator/health").with(request -> {
            request.setLocalPort(9090);
            return request;
        })).andExpect(status().isOk()).andExpect(result -> {
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("UP");
        });
        mvc.perform(MockMvcRequestBuilders.get("/actuator/prometheus").with(request -> {
            request.setLocalPort(9090);
            return request;
        })).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/actuator/health").with(request -> {
            request.setLocalPort(8090);
            return request;
        })).andExpect(status().isNotFound());
        mvc.perform(post(webhook(1)).with(request -> {
            request.setLocalPort(9090);
            return request;
        })).andExpect(status().isNotFound());
        assertThat(calls).hasValue(0);
    }
}
