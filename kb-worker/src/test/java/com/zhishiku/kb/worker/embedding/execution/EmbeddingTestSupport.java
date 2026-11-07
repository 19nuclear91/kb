package com.zhishiku.kb.worker.embedding.execution;

import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class EmbeddingTestSupport {
    private EmbeddingTestSupport() {
    }

    static WorkerExecution budget() {
        var execution = new WorkerExecution(WorkerExecution.Purpose.PARSE, 4194304, System::nanoTime,
                (action, nanos) -> () -> { }, Runnable::run);
        execution.begin(Duration.ofMinutes(1));
        return execution;
    }

    static EmbeddingProfileDefinition definition(int count) {
        var profile = new EmbeddingProfile("remote-v1", "remote", "vendor/model", "model", "1", 1024,
                "cosine", "e5-query-v1", "e5-document-v1", "query", "document", "embeddings-v1");
        return new EmbeddingProfileDefinition(profile, new EmbeddingConnection("https://supplier.invalid", "test-key", 60, count));
    }

    static WorkerEmbeddingSettings settings(int requestBytes) {
        return new WorkerEmbeddingSettings(requestBytes, 4194304, 64, 300, 10);
    }

    static Map<String, Object> body(List<String> inputs) {
        return Map.of("model", "model", "input", inputs, "dimensions", 1024);
    }

    static Map<String, Object> item(int index, int value) {
        return Map.of("index", index, "embedding", Collections.nCopies(1024, value));
    }
}
