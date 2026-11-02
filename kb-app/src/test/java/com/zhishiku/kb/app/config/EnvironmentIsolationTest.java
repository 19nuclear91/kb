package com.zhishiku.kb.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentIsolationTest {
    private ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + profile)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> new ProductionEnvironmentCheck()
                        .postProcessEnvironment(context.getEnvironment(), new SpringApplication()));
    }

    @Test
    void devExplicitlyLoadsSeedsAndEnablesDevLogin() {
        runner("dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.flyway.locations")).contains("classpath:db/dev");
            assertThat(context.getEnvironment().getProperty("kb.dev-auth.enabled")).isEqualTo("true");
        });
    }

    @Test
    void prodRefusesDevLoginEvenWithValidCredentials() {
        runner("prod").withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://db/kb", "spring.datasource.username=service",
                        "spring.datasource.password=external", "spring.data.redis.host=redis",
                        "kb.storage.endpoint=https://s3.example", "kb.storage.access-key=external",
                        "kb.storage.secret-key=external", "kb.storage.bucket=kb", "kb.dev-auth.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void safeProdExcludesSeedsAndKeepsSqlLoggingOff() {
        runner("prod").withPropertyValues(
                        "management.server.port=9081", "management.server.address=127.0.0.1",
                        "spring.datasource.url=jdbc:postgresql://db/kb", "spring.datasource.username=service",
                        "spring.datasource.password=external", "spring.data.redis.host=redis",
                        "kb.storage.endpoint=https://s3.example", "kb.storage.access-key=external",
                        "kb.storage.secret-key=external", "kb.storage.bucket=kb",
                        "kb.embedding.profiles[0].profile.profile-id=remote-v1",
                        "kb.embedding.profiles[0].profile.provider=remote",
                        "kb.embedding.profiles[0].profile.model-source=publisher/model",
                        "kb.embedding.profiles[0].profile.model=model-v1",
                        "kb.embedding.profiles[0].profile.model-revision=1",
                        "kb.embedding.profiles[0].profile.dimension=1024",
                        "kb.embedding.profiles[0].profile.distance=cosine",
                        "kb.embedding.profiles[0].profile.query-transform-version=identity-v1",
                        "kb.embedding.profiles[0].profile.document-transform-version=identity-v1",
                        "kb.embedding.profiles[0].profile.query-task-type=query",
                        "kb.embedding.profiles[0].profile.document-task-type=document",
                        "kb.embedding.profiles[0].profile.adapter-version=embeddings-v1",
                        "kb.embedding.profiles[0].connection.endpoint=https://model.invalid/v1",
                        "kb.embedding.profiles[0].connection.timeout-seconds=30",
                        "kb.embedding.profiles[0].connection.batch-size=16")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                            .isEqualTo("classpath:db/migration");
                    assertThat(context.getEnvironment().getProperty("kb.dev-auth.enabled")).isEqualTo("false");
                    assertThat(context.getEnvironment().getProperty("logging.level.com.zhishiku.kb.app.search.mapper"))
                            .isEqualToIgnoringCase("OFF");
                });
    }

    @Test
    void seedResourcesOnlyExistInDevLocation() {
        for (String name : new String[]{"V3__dev_seed.sql", "V6__dev_upload_permission.sql"}) {
            assertThat(getClass().getResource("/db/migration/" + name)).isNull();
            assertThat(getClass().getResource("/db/dev/" + name)).isNotNull();
        }
    }
}
