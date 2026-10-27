package com.zhishiku.kb.common.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingRegistryTest {

    static EmbeddingProfileDefinition definition(String id, String provider, String query, String document) {
        return new EmbeddingProfileDefinition(new EmbeddingProfile(id, provider, "publisher/model",
                "local".equals(provider) ? "local-hash-v1" : "model-v1", "revision-1", 1024, "cosine",
                query, document, "query", "document", "local".equals(provider) ? "local-hash-v1" : "embeddings-v1"),
                new EmbeddingConnection("local".equals(provider) ? null : "https://service.invalid/v1",
                        "secret", 30, 2));
    }

    @Test
    void registryResolvesOnlyExactIdentityAndReturnsImmutableClientCollection() {
        var first = definition("first", "local", "identity-v1", "identity-v1");
        var second = definition("second", "local", "e5-query-v1", "e5-document-v1");
        for (EmbeddingRole role : EmbeddingRole.values()) {
            var registry = new EmbeddingClientRegistry(List.of(first, second), role,
                    definition -> new LocalProfileEmbeddingClient(definition, role));
            assertThat(registry.clients()).hasSize(2);
            assertThat(registry.find("first", first.profile().fingerprint())).hasValueSatisfying(client -> {
                assertThat(client.role()).isEqualTo(role);
                client.embed(List.of("text")).require(first.profile(), role, 1);
            });
            assertThat(registry.find("first", second.profile().fingerprint())).isEmpty();
            assertThat(registry.find("missing", first.profile().fingerprint())).isEmpty();
            assertThatThrownBy(() -> registry.clients().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void factoryStubCannotSubstituteWrongIdentityOrRole() {
        var definition = definition("first", "local", "identity-v1", "identity-v1");
        var other = definition("second", "local", "identity-v1", "identity-v1");
        assertThatThrownBy(() -> new EmbeddingClientRegistry(List.of(definition), EmbeddingRole.QUERY,
                ignored -> new LocalProfileEmbeddingClient(other, EmbeddingRole.QUERY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingClientRegistry(List.of(definition), EmbeddingRole.QUERY,
                ignored -> new LocalProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingClientRegistry(List.of(definition, definition), EmbeddingRole.QUERY,
                ignored -> new LocalProfileEmbeddingClient(definition, EmbeddingRole.QUERY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingClientRegistry(List.of(), EmbeddingRole.QUERY, ignored -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transformsArePairedAndActuallyAppliedWithoutChangingOriginalText() {
        var definition = definition("e5", "remote", "e5-query-v1", "e5-document-v1");
        assertThat(definition.transform(EmbeddingRole.QUERY, List.of("text"))).containsExactly("query: text");
        assertThat(definition.transform(EmbeddingRole.DOCUMENT, List.of("text"))).containsExactly("passage: text");
        assertThatThrownBy(() -> definition.transform(EmbeddingRole.QUERY, List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition("bad", "remote", "unknown", "identity-v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition("bad", "remote", "e5-query-v1", "identity-v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition("bad", "unknown", "identity-v1", "identity-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectionsValidateLimitsAndNeverPrintCredentialsOrEndpoint() {
        for (String endpoint : List.of("invalid", "file:///tmp", "http://user:pass@host", "https://host?q=secret")) {
            assertThatThrownBy(() -> new EmbeddingConnection(endpoint, "secret", 30, 2))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(endpoint)
                    .hasMessageNotContaining("secret");
        }
        assertThatThrownBy(() -> new EmbeddingConnection(null, null, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingConnection(null, null, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EmbeddingConnection("https://host", "secret", 30, 2).toString())
                .doesNotContain("https://host", "secret");
    }
}
