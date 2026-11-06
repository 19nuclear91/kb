package com.zhishiku.kb.worker.telemetry;

import com.zhishiku.kb.worker.config.StartupEnvironmentCheck;
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
    void devSharesBusinessPortAndProdRequiresSeparateExplicitPortAndAddress() {
        runner("dev").run(context -> {
            new StartupEnvironmentCheck().postProcessEnvironment(context.getEnvironment(), null);
            assertThat(ManagementPortType.get(context.getEnvironment())).isEqualTo(ManagementPortType.SAME);
        });
        runner("prod").run(context -> assertThatThrownBy(() -> new StartupEnvironmentCheck()
                .postProcessEnvironment(context.getEnvironment(), null)).hasMessageContaining("management.server.port"));
        runner("prod").withPropertyValues("KB_MANAGEMENT_PORT=9083").run(context -> assertThatThrownBy(
                () -> new StartupEnvironmentCheck().postProcessEnvironment(context.getEnvironment(), null))
                .hasMessageContaining("management.server.address"));
        runner("prod").withPropertyValues("KB_MANAGEMENT_PORT=9083", "KB_MANAGEMENT_ADDRESS=127.0.0.1").run(context -> {
            new StartupEnvironmentCheck().postProcessEnvironment(context.getEnvironment(), null);
            var properties = Binder.get(context.getEnvironment()).bind("management.server", ManagementServerProperties.class).get();
            assertThat(properties.getPort()).isEqualTo(9083);
            assertThat(properties.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
            assertThat(ManagementPortType.get(context.getEnvironment())).isEqualTo(ManagementPortType.DIFFERENT);
        });
    }

    private static ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile, "KB_MANAGEMENT_PORT=", "KB_MANAGEMENT_ADDRESS=",
                        "spring.datasource.url=jdbc:postgresql://db/kb", "spring.datasource.username=service", "spring.datasource.password=external",
                        "kb.storage.endpoint=https://s3.invalid", "kb.storage.access-key=external", "kb.storage.secret-key=external", "kb.storage.bucket=documents",
                        "kb.embedding.profiles[0].profile.profile-id=remote-v1", "kb.embedding.profiles[0].profile.provider=remote",
                        "kb.embedding.profiles[0].profile.model-source=vendor/model", "kb.embedding.profiles[0].profile.model=model-v1",
                        "kb.embedding.profiles[0].profile.model-revision=1", "kb.embedding.profiles[0].profile.dimension=1024",
                        "kb.embedding.profiles[0].profile.distance=cosine", "kb.embedding.profiles[0].profile.query-transform-version=identity-v1",
                        "kb.embedding.profiles[0].profile.document-transform-version=identity-v1", "kb.embedding.profiles[0].profile.query-task-type=query",
                        "kb.embedding.profiles[0].profile.document-task-type=document", "kb.embedding.profiles[0].profile.adapter-version=embeddings-v1",
                        "kb.embedding.profiles[0].connection.endpoint=https://model.invalid/v1", "kb.embedding.profiles[0].connection.timeout-seconds=30",
                        "kb.embedding.profiles[0].connection.batch-size=16");
    }
}
