package com.zhishiku.kb.worker.embedding;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalHashEmbeddingClientTest {

    @Test
    void producesDeterministicNormalizedVectorsWithConfiguredDimension() {
        var client = new LocalHashEmbeddingClient(
                new EmbeddingProperties("local", "", "", "test-local", 32, 8, 10));

        float[] first = client.embed(List.of("知识库")).getFirst();
        float[] second = client.embed(List.of("知识库")).getFirst();
        double norm = 0;
        for (float value : first) norm += value * value;

        assertThat(first).hasSize(32);
        assertThat(first).containsExactly(second);
        assertThat(norm).isCloseTo(1.0, Offset.offset(0.00001));
    }
}
