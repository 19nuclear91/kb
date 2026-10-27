package com.zhishiku.kb.app.search.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingProfilesPropertiesTest {
    private Map<String, Object> settings() {
        String prefix = "kb.embedding.profiles[0].";
        Map<String, Object> settings = new HashMap<>();
        Map.ofEntries(Map.entry("profile.profile-id", "dev-v1"), Map.entry("profile.provider", "local"),
                        Map.entry("profile.model-source", "kb/local-hash"), Map.entry("profile.model", "local-hash-v1"),
                        Map.entry("profile.model-revision", "1"), Map.entry("profile.dimension", 1024),
                        Map.entry("profile.distance", "cosine"), Map.entry("profile.query-transform-version",
                                "identity-v1"),
                        Map.entry("profile.document-transform-version", "identity-v1"), Map.entry("profile" +
                                ".query-task-type", "query"),
                        Map.entry("profile.document-task-type", "document"), Map.entry("profile.adapter-version",
                                "local-hash-v1"),
                        Map.entry("connection.timeout-seconds", 30), Map.entry("connection.batch-size", 16))
                .forEach((key, value) -> settings.put(prefix + key, value));
        return settings;
    }

    private EmbeddingProfilesProperties bind(Map<String, Object> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bind("kb.embedding", Bindable.of(EmbeddingProfilesProperties.class)).get();
    }

    @Test
    void bindsProfilesAndBuildsRegistryWithoutStartingApplicationOrTransport() {
        var properties = bind(settings());
        var registry = new EmbeddingProfilesConfiguration().embeddingClientRegistry(properties, RestClient.builder());
        var profile = properties.profiles().getFirst().profile();
        var client = registry.find(profile.profileId(), profile.fingerprint()).orElseThrow();
        assertThat(client.role()).isEqualTo(EmbeddingRole.QUERY);
        client.embed(List.of("text")).require(profile, EmbeddingRole.QUERY, 1);
        assertThat(properties.toString()).isEqualTo("EmbeddingProfilesProperties[count=1]");
    }

    @Test
    void rejectsUnknownProviderTransformsAndMissingFields() {
        for (String key : List.of("profile.provider", "profile.query-transform-version",
                "profile.document-transform-version", "profile.adapter-version", "profile.query-task-type")) {
            var settings = settings();
            settings.put("kb.embedding.profiles[0]." + key, "unknown");
            assertThatThrownBy(() -> bind(settings)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
        for (String key : List.of("profile.model-revision", "profile.model-source", "profile.profile-id",
                "profile.dimension", "profile.distance", "connection.timeout-seconds", "connection.batch-size")) {
            var settings = settings();
            settings.remove("kb.embedding.profiles[0]." + key);
            assertThatThrownBy(() -> bind(settings)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
