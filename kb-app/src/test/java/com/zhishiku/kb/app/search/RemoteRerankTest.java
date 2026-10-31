package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchHttpTransport;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.rerank.OpenAiCompatibleRerankClient;
import com.zhishiku.kb.app.search.rerank.RerankProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class RemoteRerankTest {
    static Stream<Object> invalidResults() {
        return Stream.of(List.of(), List.of(item(0, 1)), List.of(item(0, 1), item(1, 2), item(2, 3)),
                List.of(item(0, 1), item(0, 2)), List.of(item(-1, 1), item(1, 2)),
                List.of(item(0, 1), item(2, 2)), List.of(item(0.5, 1), item(1, 2)),
                List.of(item(0.0, 1), item(1, 2)), List.of(item(new BigDecimal("0.0"), 1), item(1, 2)),
                List.of(item("0", 1), item(1, 2)), List.of(item(4294967296L, 1), item(1, 2)),
                List.of(item(new BigInteger("9999999999999999999999"), 1), item(1, 2)),
                List.of(item(0, Double.NaN), item(1, 2)), List.of(item(0, Double.POSITIVE_INFINITY), item(1, 2)),
                List.of(Map.of("index", 0), item(1, 2)), List.of(item(0, "1"), item(1, 2)),
                List.of("bad", item(1, 2)), Map.of());
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void anyInvalidEntryRejectsTheEntireRemoteRanking(Object results) {
        var transport = mock(SearchHttpTransport.class);
        when(transport.post(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Map.of("results", results));
        try (var current = mockStatic(SearchContext.class)) {
            current.when(SearchContext::current).thenReturn(mock(SearchContext.class));
            assertThatThrownBy(() -> client(transport).rerank("q", List.of(hit(0), hit(1)), 2))
                    .isInstanceOfSatisfying(SearchStageFailure.class,
                            e -> assertThat(e.reason()).isEqualTo(SearchStageFailure.Reason.INVALID_RESPONSE));
        }
    }

    @Test
    void tiesUseOriginalIndexAndAllContentComesFromOriginalCandidates() {
        var transport = mock(SearchHttpTransport.class);
        var first = hit(0);
        var second = hit(1);
        when(transport.post(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Map.of("results", List.of(
                Map.of("index", 1, "relevance_score", 2, "content", "forged", "docId", 99), item(0, 2))));
        try (var current = mockStatic(SearchContext.class)) {
            current.when(SearchContext::current).thenReturn(mock(SearchContext.class));
            var result = client(transport).rerank("q", List.of(first, second), 5);
            assertThat(result).containsExactly(first, second);
            assertThat(result.get(1)).isSameAs(second);
        }
    }

    private static OpenAiCompatibleRerankClient client(SearchHttpTransport transport) {
        return new OpenAiCompatibleRerankClient(new RerankProperties("remote", "https://unused.invalid", null, "rank", 30),
                transport, SearchLimitsProperties.defaults());
    }

    private static Map<String, Object> item(Object index, Object score) {
        return Map.of("index", index, "relevance_score", score);
    }

    private static ChunkHit hit(long id) {
        return new ChunkHit(id, 10L, 20L, 1, "title", 1L, (short) 0, 0, "text", null, null, null,
                null, null, null, null, null, 0.5);
    }
}
