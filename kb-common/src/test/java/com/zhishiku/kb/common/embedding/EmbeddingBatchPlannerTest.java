package com.zhishiku.kb.common.embedding;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingBatchPlannerTest {
    private final EmbeddingProfileDefinition definition = EmbeddingRegistryTest.definition(
            "remote", "remote", "e5-query-v1", "e5-document-v1");

    @Test
    void optionalPlannerReceivesTransformedInputsAndPreservesPositions() {
        var calls = new AtomicInteger();
        var sent = new ArrayList<Object>();
        var client = new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT, body -> {
            sent.add(body.get("input"));
            int number = calls.incrementAndGet();
            return Map.of("data", List.of(Map.of("index", 0, "embedding", Collections.nCopies(1024, number))));
        }, (profile, inputs, budget) -> {
            assertThat(inputs).containsExactly("passage: one", "passage: two");
            return List.of(1, 2);
        });
        var result = client.embed(List.of("one", "two"));
        assertThat(sent).containsExactly(List.of("passage: one"), List.of("passage: two"));
        assertThat(result.vector(0)[0]).isEqualTo(1);
        assertThat(result.vector(1)[0]).isEqualTo(2);
    }

    @Test
    void malformedPlansAreRejectedBeforeAnyTransport() {
        var calls = new AtomicInteger();
        for (List<Integer> plan : List.of(List.of(0), List.of(1), List.of(2, 2), List.of(3))) {
            var client = new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT, body -> {
                calls.incrementAndGet();
                return Map.of();
            }, (profile, inputs, budget) -> plan);
            assertThatThrownBy(() -> client.embed(List.of("one", "two"))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls.get()).isZero();
    }
}
