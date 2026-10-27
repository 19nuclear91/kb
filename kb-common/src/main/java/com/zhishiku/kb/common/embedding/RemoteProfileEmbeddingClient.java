package com.zhishiku.kb.common.embedding;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RemoteProfileEmbeddingClient implements ProfileEmbeddingClient {

    private final EmbeddingProfileDefinition definition;
    private final EmbeddingRole role;
    private final RemoteEmbeddingTransport transport;

    public RemoteProfileEmbeddingClient(EmbeddingProfileDefinition definition, EmbeddingRole role,
                                        RemoteEmbeddingTransport transport) {
        if (!"remote".equals(definition.profile().provider())) {
            throw new IllegalArgumentException("remote embedding requires remote provider");
        }
        this.definition = definition;
        this.role = Objects.requireNonNull(role, "role");
        this.transport = Objects.requireNonNull(transport, "transport");
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
        List<String> inputs = definition.transform(role, texts);
        List<float[]> vectors = new ArrayList<>(inputs.size());
        for (int start = 0; start < inputs.size(); ) {
            int end = start + Math.min(definition.connection().batchSize(), inputs.size() - start);
            Map<String, Object> response = transport.post(Map.of("model", profile().model(),
                    "input", inputs.subList(start, end), "dimensions", profile().dimension()));
            vectors.addAll(parse(response, end - start));
            start = end;
        }
        return new EmbeddingResult(profile(), role, vectors);
    }

    private List<float[]> parse(Map<String, Object> response, int expectedCount) {
        if (response == null || (response.containsKey("model") && !profile().model().equals(response.get("model")))) {
            throw invalid();
        }
        if (!(response.get("data") instanceof List<?> items) || items.size() != expectedCount) {
            throw invalid();
        }
        float[][] vectors = new float[expectedCount][];
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> entry) || !(entry.get("index") instanceof Number number)) {
                throw invalid();
            }
            int index;
            try {
                index = new BigDecimal(number.toString()).intValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                throw invalid();
            }
            if (index < 0 || index >= expectedCount || vectors[index] != null
                    || !(entry.get("embedding") instanceof List<?> values)
                    || values.size() != profile().dimension()) {
                throw invalid();
            }
            float[] vector = new float[values.size()];
            boolean nonzero = false;
            for (int i = 0; i < values.size(); i++) {
                if (!(values.get(i) instanceof Number value) || !Float.isFinite(value.floatValue())) {
                    throw invalid();
                }
                vector[i] = value.floatValue();
                nonzero |= vector[i] != 0f;
            }
            if (!nonzero) throw invalid();
            vectors[index] = vector;
        }
        return Arrays.asList(vectors);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("invalid embedding response");
    }
}
