package com.zhishiku.kb.common.embedding;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class EmbeddingClientRegistry {

    private final Map<String, ProfileEmbeddingClient> clients;

    public EmbeddingClientRegistry(List<EmbeddingProfileDefinition> definitions, EmbeddingRole role,
                                   Function<EmbeddingProfileDefinition, ProfileEmbeddingClient> factory) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("kb.embedding.profiles must be explicit and non-empty");
        }
        Map<String, ProfileEmbeddingClient> byId = new LinkedHashMap<>();
        for (EmbeddingProfileDefinition definition : definitions) {
            String id = definition.profile().profileId();
            if (byId.containsKey(id)) {
                throw new IllegalArgumentException("duplicate embedding profileId");
            }
            ProfileEmbeddingClient client = factory.apply(definition);
            if (client == null || !definition.profile().compatibleWith(client.profile()) || client.role() != role) {
                throw new IllegalArgumentException("embedding client factory returned incompatible identity or role");
            }
            byId.put(id, client);
        }
        clients = Map.copyOf(byId);
    }

    public Optional<ProfileEmbeddingClient> find(String profileId, String fingerprint) {
        ProfileEmbeddingClient client = clients.get(profileId);
        return client != null && client.profile().hasIdentity(profileId, fingerprint)
                ? Optional.of(client) : Optional.empty();
    }

    public Collection<ProfileEmbeddingClient> clients() {
        return clients.values();
    }
}
