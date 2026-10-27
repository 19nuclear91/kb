package com.zhishiku.kb.common.embedding;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteProfileEmbeddingClientTest {

    private final EmbeddingProfileDefinition definition = EmbeddingRegistryTest.definition(
            "remote", "remote", "e5-query-v1", "e5-document-v1");

    @Test
    void batchesAndRestoresOrderForBothRoles() {
        for (EmbeddingRole role : EmbeddingRole.values()) {
            List<Map<String, Object>> requests = new ArrayList<>();
            var client = new RemoteProfileEmbeddingClient(definition, role, body -> {
                requests.add(body);
                List<?> inputs = (List<?>) body.get("input");
                return Map.of("model", "model-v1", "data", inputs.size() == 2
                        ? List.of(item(1, 2f), item(0, 1f)) : List.of(item(0, 3f)));
            });
            var result = client.embed(List.of("one", "two", "three"));
            result.require(definition.profile(), role, 3);
            assertThat(result.vector(0)[0]).isEqualTo(1f);
            assertThat(result.vector(1)[0]).isEqualTo(2f);
            assertThat(result.vector(2)[0]).isEqualTo(3f);
            assertThat(requests).hasSize(2);
            String prefix = role == EmbeddingRole.QUERY ? "query: " : "passage: ";
            assertThat(requests.getFirst()).containsEntry("model", "model-v1").containsEntry("dimensions", 1024)
                    .containsEntry("input", List.of(prefix + "one", prefix + "two"));
        }
    }

    @Test
    void rejectsDuplicateMissingExtraOutOfRangeAndNonIntegralIndices() {
        List<List<?>> invalid = List.of(
                List.of(item(0, 1f), item(0, 2f)), List.of(item(0, 1f)),
                List.of(item(0, 1f), item(1, 1f), item(2, 1f)),
                List.of(item(-1, 1f), item(1, 1f)), List.of(item(0, 1f), item(2, 1f)),
                List.of(item(0.5, 1f), item(1, 1f)), List.of(item(0, 1f), item(4294967297L, 1f)),
                List.of(Map.of("embedding", values(1f)), item(1, 1f)),
                List.of(item(0, 1f), Map.of("index", "1", "embedding", values(1f))),
                List.of(item(0, 1f), "not-an-item"));
        for (List<?> data : invalid) {
            assertInvalid(Map.of("data", data), 2);
        }
    }

    @Test
    void rejectsBadShapeNumbersModelsAndExtraQueryResults() {
        assertInvalid(Map.of(), 1);
        assertInvalid(null, 1);
        assertInvalid(Map.of("model", "unexpected-secret", "data", List.of(item(0, 1f))), 1);
        assertInvalid(Map.of("data", List.of(item(0, 1f), item(1, 1f))), 1);
        for (Object value : List.of(0f, Float.NaN, Float.POSITIVE_INFINITY, Double.MAX_VALUE, "bad", true)) {
            List<Object> vector = new ArrayList<>(values(0f));
            vector.set(0, value);
            assertInvalid(Map.of("data", List.of(Map.of("index", 0, "embedding", vector))), 1);
        }
        assertInvalid(Map.of("data", List.of(Map.of("index", 0, "embedding", List.of(1f)))), 1);
        Map<String, Object> nullModel = new HashMap<>();
        nullModel.put("model", null);
        nullModel.put("data", List.of(item(0, 1f)));
        assertInvalid(nullModel, 1);
    }

    @Test
    void emptyBatchMakesNoTransportCallsAndFailureDoesNotReturnPartialResults() {
        AtomicInteger calls = new AtomicInteger();
        var client = new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT, body -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("timeout");
            return Map.of("data", List.of(item(0, 1f), item(1, 1f)));
        });
        assertThat(client.embed(List.of()).size()).isZero();
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> client.embed(List.of("one", "two", "three")))
                .isInstanceOf(IllegalStateException.class).hasMessage("timeout");
        assertThat(calls).hasValue(2);
    }

    private void assertInvalid(Map<String, Object> response, int count) {
        var client = new RemoteProfileEmbeddingClient(definition, EmbeddingRole.QUERY, body -> response);
        assertThatThrownBy(() -> client.embed(Collections.nCopies(count, "private-input")))
                .isInstanceOf(IllegalStateException.class).hasMessage("invalid embedding response");
    }

    private static Map<String, Object> item(Number index, float value) {
        return Map.of("index", index, "embedding", values(value));
    }

    private static List<Float> values(float first) {
        List<Float> values = new ArrayList<>(Collections.nCopies(1024, 0f));
        values.set(0, first);
        return values;
    }
}
