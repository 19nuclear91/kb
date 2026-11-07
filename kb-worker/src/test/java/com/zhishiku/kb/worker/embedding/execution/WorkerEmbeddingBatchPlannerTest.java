package com.zhishiku.kb.worker.embedding.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.RemoteProfileEmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerEmbeddingBatchPlannerTest {
    @Test
    void exactUtf8EncodingIncludesPrefixEscapingAndEnvelope() throws Exception {
        var inputs = List.of("中文😀\"\n", "第二条\\\t", "第三条");
        var definition = EmbeddingTestSupport.definition(16);
        var transformed = definition.transform(EmbeddingRole.DOCUMENT, inputs);
        int maximum = new ObjectMapper().writeValueAsBytes(EmbeddingTestSupport.body(transformed.subList(0, 2))).length;
        var settings = EmbeddingTestSupport.settings(maximum);
        var json = new WorkerEmbeddingJson(settings);
        var planner = new WorkerEmbeddingBatchPlanner(settings, json);
        var budget = EmbeddingTestSupport.budget();
        assertThat(planner.plan(definition, transformed, budget)).containsExactly(2, 3);
        byte[] encoded = json.encode(EmbeddingTestSupport.body(transformed.subList(0, 2)), budget);
        assertThat(encoded).hasSize(maximum);
        assertThat(new ObjectMapper().readTree(encoded).get("input").get(0).asText()).isEqualTo(transformed.getFirst());
        var smaller = EmbeddingTestSupport.settings(maximum - 1);
        assertThat(new WorkerEmbeddingBatchPlanner(smaller, new WorkerEmbeddingJson(smaller))
                .plan(definition, transformed, budget)).startsWith(1);
    }

    @Test
    void lastOversizedInputFailsBeforeAnyRequestAndDoesNotLeakInput() {
        var settings = EmbeddingTestSupport.settings(128);
        var calls = new AtomicInteger();
        var client = new RemoteProfileEmbeddingClient(EmbeddingTestSupport.definition(1), EmbeddingRole.DOCUMENT,
                body -> {
                    calls.incrementAndGet();
                    return Map.of();
                }, new WorkerEmbeddingBatchPlanner(settings, new WorkerEmbeddingJson(settings)));
        assertThatThrownBy(() -> client.embed(List.of("small", "sensitive-document".repeat(100)), EmbeddingTestSupport.budget()))
                .isInstanceOf(WorkerEmbeddingFailure.class).hasMessage("Worker embedding: REQUEST_LIMIT");
        assertThat(calls.get()).isZero();
    }

    @Test
    void countLimitAppliesEvenWhenAllBytesFit() {
        var definition = EmbeddingTestSupport.definition(2);
        var settings = WorkerEmbeddingSettings.defaults();
        var planner = new WorkerEmbeddingBatchPlanner(settings, new WorkerEmbeddingJson(settings));
        assertThat(planner.plan(definition, List.of("a", "b", "c", "d", "e"), EmbeddingTestSupport.budget()))
                .containsExactly(2, 4, 5);
        assertThat(planner.plan(definition, new ArrayList<>(), EmbeddingTestSupport.budget())).isEmpty();
    }
}
