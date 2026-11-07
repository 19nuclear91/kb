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
    private final EmbeddingBatchPlanner planner;

    public RemoteProfileEmbeddingClient(EmbeddingProfileDefinition definition, EmbeddingRole role,
                                         RemoteEmbeddingTransport transport) {
        this(definition, role, transport, EmbeddingBatchPlanner.byCount());
    }

    public RemoteProfileEmbeddingClient(EmbeddingProfileDefinition definition, EmbeddingRole role,
                                        RemoteEmbeddingTransport transport, EmbeddingBatchPlanner planner) {
        if (!"remote".equals(definition.profile().provider())) {
            throw new IllegalArgumentException("remote embedding requires remote provider");
        }
        this.definition = definition;
        this.role = Objects.requireNonNull(role, "role");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.planner = Objects.requireNonNull(planner, "planner");
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
        return embedInternal(texts, null);
    }

    @Override
    public EmbeddingResult embed(List<String> texts, CallBudget budget) {
        Objects.requireNonNull(budget, "budget").check();
        EmbeddingResult result = embedInternal(texts, budget);
        budget.check();
        return result;
    }

    private EmbeddingResult embedInternal(List<String> texts, CallBudget budget) {
        List<String> inputs = definition.transform(role, texts);
        List<Integer> ends = List.copyOf(planner.plan(definition, inputs, budget));
        int previous = 0;
        for (int end : ends) {
            if (end <= previous || end > inputs.size() || end - previous > definition.connection().batchSize()) {
                throw new IllegalArgumentException("invalid embedding batch plan");
            }
            previous = end;
        }
        if (previous != inputs.size()) {
            throw new IllegalArgumentException("incomplete embedding batch plan");
        }
        List<float[]> vectors = new ArrayList<>(inputs.size());
        int start = 0;
        for (int end : ends) {
            if (budget != null) {
                budget.check();
            }
            Map<String, Object> body = Map.of("model", profile().model(),
                    "input", inputs.subList(start, end), "dimensions", profile().dimension());
            Map<String, Object> response = budget == null ? transport.post(body) : transport.post(body, budget);
            if (budget != null) {
                budget.check();
            }
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
            if (!nonzero) {
                throw invalid();
            }
            vectors[index] = vector;
        }
        return Arrays.asList(vectors);
    }

    private static EmbeddingProtocolFailure invalid() {
        return new EmbeddingProtocolFailure();
    }
}
