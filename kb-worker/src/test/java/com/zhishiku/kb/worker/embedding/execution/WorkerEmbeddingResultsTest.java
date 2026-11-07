package com.zhishiku.kb.worker.embedding.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.embedding.EmbeddingProtocolFailure;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.RemoteProfileEmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerEmbeddingResultsTest {
    @Test
    void byteLimitedSubBatchesRestoreLocalIndicesToWholeInputOrder() throws Exception {
        var definition = EmbeddingTestSupport.definition(16);
        var inputs = List.of("a", "b", "c", "d", "e");
        var transformed = definition.transform(EmbeddingRole.DOCUMENT, inputs);
        var settings = EmbeddingTestSupport.settings(new ObjectMapper()
                .writeValueAsBytes(EmbeddingTestSupport.body(transformed.subList(0, 2))).length);
        var calls = new AtomicInteger();
        var client = new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT, body -> {
            int base = calls.incrementAndGet() * 10;
            int count = ((List<?>) body.get("input")).size();
            var items = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < count; index++) {
                items.add(EmbeddingTestSupport.item(index, base + index));
            }
            Collections.reverse(items);
            return Map.of("data", items);
        }, new WorkerEmbeddingBatchPlanner(settings, new WorkerEmbeddingJson(settings)));
        var result = client.embed(inputs, EmbeddingTestSupport.budget());
        result.require(definition.profile(), EmbeddingRole.DOCUMENT, 5);
        assertThat(result.vectors().stream().map(vector -> vector[0])).containsExactly(10f, 11f, 20f, 21f, 30f);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void invalidSecondBatchCannotReturnSuccessfulPrefix() {
        var definition = EmbeddingTestSupport.definition(2);
        var settings = WorkerEmbeddingSettings.defaults();
        var calls = new AtomicInteger();
        var client = new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT, body -> {
            int call = calls.incrementAndGet();
            return Map.of("data", List.of(EmbeddingTestSupport.item(0, 1),
                    EmbeddingTestSupport.item(call == 1 ? 1 : 0, 2)));
        }, new WorkerEmbeddingBatchPlanner(settings, new WorkerEmbeddingJson(settings)));
        assertThatThrownBy(() -> client.embed(List.of("a", "b", "c", "d", "e"), EmbeddingTestSupport.budget()))
                .isInstanceOf(EmbeddingProtocolFailure.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void stopAfterFirstSubBatchPreventsSecondRequest() {
        var definition = EmbeddingTestSupport.definition(1);
        var settings = WorkerEmbeddingSettings.defaults();
        var calls = new AtomicInteger();
        var budget = EmbeddingTestSupport.budget();
        var client = new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT, body -> {
            calls.incrementAndGet();
            budget.stop(WorkerEmbeddingFailure.Reason.LEASE_LOST);
            return Map.of("data", List.of(EmbeddingTestSupport.item(0, 1)));
        }, new WorkerEmbeddingBatchPlanner(settings, new WorkerEmbeddingJson(settings)));
        assertThatThrownBy(() -> client.embed(List.of("a", "b"), budget))
                .isInstanceOf(WorkerEmbeddingFailure.class).hasMessage("Worker embedding: LEASE_LOST");
        assertThat(calls.get()).isOne();
    }
}
