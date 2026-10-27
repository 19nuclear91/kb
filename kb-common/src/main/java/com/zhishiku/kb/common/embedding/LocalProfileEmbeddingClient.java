package com.zhishiku.kb.common.embedding;

import java.util.List;
import java.util.Objects;

public final class LocalProfileEmbeddingClient implements ProfileEmbeddingClient {

    private final EmbeddingProfileDefinition definition;
    private final EmbeddingRole role;

    public LocalProfileEmbeddingClient(EmbeddingProfileDefinition definition, EmbeddingRole role) {
        if (!"local".equals(definition.profile().provider())) {
            throw new IllegalArgumentException("local embedding requires local provider");
        }
        this.definition = definition;
        this.role = Objects.requireNonNull(role, "role");
    }

    @Override
    public EmbeddingProfile profile() {
        return definition.profile();
    }

    @Override
    public EmbeddingRole role() {
        return role;
    }

    @Override
    public EmbeddingResult embed(List<String> texts) {
        return new EmbeddingResult(profile(), role, definition.transform(role, texts).stream()
                .map(text -> EmbeddingMath.localHash(text, profile().dimension())).toList());
    }
}
