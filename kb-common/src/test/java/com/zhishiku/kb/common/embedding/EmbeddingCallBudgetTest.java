package com.zhishiku.kb.common.embedding;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingCallBudgetTest {
    @Test
    void queryPassesBudgetButOriginalDocumentBatchRemainsIndependent() {
        var definition = EmbeddingRegistryTest.definition("remote", "remote", "e5-query-v1", "e5-document-v1");
        var batches = new AtomicInteger();
        var queries = new AtomicInteger();
        var expired = new AtomicInteger();
        var budget = new CallBudget() {
            public long remainingNanos() {
                return 10;
            }
            public int maxResponseBytes() {
                return 1024;
            }
            public void check() {
                if (expired.get() > 0) {
                    throw new IllegalStateException("expired");
                }
            }
            public Registration onCancel(Runnable action) {
                return () -> { };
            }
            public Registration limitTo(long nanos) {
                return () -> { };
            }
        };
        var transport = new RemoteEmbeddingTransport() {
            public Map<String, Object> post(Map<String, Object> request) {
                batches.incrementAndGet();
                return response();
            }
            public Map<String, Object> post(Map<String, Object> request, CallBudget supplied) {
                assertThat(supplied).isSameAs(budget);
                queries.incrementAndGet();
                return response();
            }
        };
        String fingerprint = definition.profile().fingerprint();
        var query = new RemoteProfileEmbeddingClient(definition, EmbeddingRole.QUERY, transport);
        assertThat(query.embed(List.of("q"), budget).size()).isOne();
        assertThat(queries.get()).isOne();
        expired.set(1);
        assertThatThrownBy(() -> query.embed(List.of("q"), budget)).hasMessage("expired");
        assertThat(queries.get()).isOne();
        assertThat(new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT, transport)
                .embed(List.of("doc")).size()).isOne();
        assertThat(batches.get()).isOne();
        assertThat(definition.profile().fingerprint()).isEqualTo(fingerprint);
    }

    private static Map<String, Object> response() {
        return Map.of("data", List.of(Map.of("index", 0, "embedding", Collections.nCopies(1024, 1f))));
    }
}
