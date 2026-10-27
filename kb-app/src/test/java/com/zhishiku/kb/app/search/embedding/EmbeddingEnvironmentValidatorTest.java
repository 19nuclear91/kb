package com.zhishiku.kb.app.search.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingEnvironmentValidatorTest {
    static MockEnvironment valid(String activeProfile, String provider) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfile);
        String p = "kb.embedding.profiles[0].";
        boolean local = "local".equals(provider);
        environment.withProperty(p + "profile.profile-id", "profile-v1")
                .withProperty(p + "profile.provider", provider)
                .withProperty(p + "profile.model-source", "publisher/model")
                .withProperty(p + "profile.model", local ? "local-hash-v1" : "model-v1")
                .withProperty(p + "profile.model-revision", "1")
                .withProperty(p + "profile.dimension", "1024")
                .withProperty(p + "profile.distance", "cosine")
                .withProperty(p + "profile.query-transform-version", "identity-v1")
                .withProperty(p + "profile.document-transform-version", "identity-v1")
                .withProperty(p + "profile.query-task-type", "query")
                .withProperty(p + "profile.document-task-type", "document")
                .withProperty(p + "profile.adapter-version", local ? "local-hash-v1" : "embeddings-v1")
                .withProperty(p + "connection.timeout-seconds", "30")
                .withProperty(p + "connection.batch-size", "16");
        if (!local) environment.withProperty(p + "connection.endpoint", "https://model.invalid/v1")
                .withProperty(p + "connection.api-key", "secret-value");
        return environment;
    }

    @Test
    void acceptsExplicitRemoteProductionAndLocalDevOnly() {
        EmbeddingEnvironmentValidator.validate(valid("prod", "remote"));
        EmbeddingEnvironmentValidator.validate(valid("dev", "local"));
        assertRejected(valid("prod", "local"));
        var mixed = valid("dev", "local");
        mixed.setActiveProfiles("dev", "prod");
        assertRejected(mixed);
        var implicitDev = valid("prod", "local");
        implicitDev.setActiveProfiles();
        implicitDev.setDefaultProfiles("dev");
        assertRejected(implicitDev);
        assertRejected(new MockEnvironment());
    }

    @Test
    void rejectsLegacySettingsInvalidShapeAndLimitsWithoutLeakingRawValues() {
        for (String key : new String[]{"kb.embedding.provider", "kb.search.embedding.provider",
                "kb.embedding.base-url", "kb.search.embedding.api-key"}) {
            assertRejected(valid("prod", "remote").withProperty(key, "secret-value"));
        }
        for (String[] setting : new String[][]{{"profile.provider", "unknown"}, {"profile.dimension", "768"},
                {"profile.distance", "l2"}, {"profile.model", "local-hash-v1"}, {"connection.timeout-seconds", "0"},
                {"connection.batch-size", "-1"}, {"connection.endpoint", "secret-value"},
                {"connection.timeout-seconds", "secret-value"}, {"profile.model-revision", ""}}) {
            assertRejected(valid("prod", "remote").withProperty("kb.embedding.profiles[0]." + setting[0], setting[1]));
        }
    }

    private void assertRejected(MockEnvironment environment) {
        assertThatThrownBy(() -> EmbeddingEnvironmentValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret-value")
                .hasMessageNotContaining("https://model.invalid").hasNoCause();
    }
}
