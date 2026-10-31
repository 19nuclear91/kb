package com.zhishiku.kb.app.search.rerank;

import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.execution.SearchCallBudget;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchHttpTransport;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 可选的通用 /rerank 适配器，不引入 Chat 客户端。
 */
@Component
@ConditionalOnProperty(name = "kb.search.rerank.provider", havingValue = "remote")
public class OpenAiCompatibleRerankClient implements RerankClient {

    private final RerankProperties props;
    private final SearchHttpTransport transport;
    private final SearchLimitsProperties limits;
    private final URI endpoint;

    public OpenAiCompatibleRerankClient(RerankProperties props, SearchHttpTransport transport, SearchLimitsProperties limits) {
        this.props = props;
        this.transport = transport;
        this.limits = limits;
        this.endpoint = URI.create(stripTrailingSlash(props.baseUrl()) + "/rerank");
    }

    @Override
    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates, int topK) {
        if (candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        int expected = Math.min(topK, candidates.size());
        List<String> documents = candidates.stream().map(ChunkHit::content).toList();
        Map<String, Object> response = transport.post(endpoint, props.apiKey(),
                Map.of("model", props.model(), "query", query, "documents", documents, "top_n", expected),
                new SearchCallBudget(SearchContext.current(), limits.maxRerankResponseBytes()),
                limits.maxRerankRequestBytes(), props.timeoutSeconds());
        Object raw = response == null ? null : response.get("results");
        if (!(raw instanceof List<?> results) || results.size() != expected) {
            throw invalid();
        }
        List<Ranked> ranked = new ArrayList<>();
        boolean[] seen = new boolean[candidates.size()];
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> map)
                    || !(map.get("relevance_score") instanceof Number number)) {
                throw invalid();
            }
            Object index = map.get("index");
            if (!(index instanceof Integer || index instanceof Long || index instanceof BigInteger)) {
                throw invalid();
            }
            int i;
            try {
                i = new BigInteger(index.toString()).intValueExact();
            } catch (ArithmeticException overflow) {
                throw invalid();
            }
            double score = number.doubleValue();
            if (i < 0 || i >= candidates.size() || seen[i] || !Double.isFinite(score)) {
                throw invalid();
            }
            seen[i] = true;
            ranked.add(new Ranked(i, score));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparingInt(Ranked::index));
        return ranked.stream().map(r -> candidates.get(r.index())).toList();
    }

    private static SearchStageFailure invalid() {
        return new SearchStageFailure(SearchStageFailure.Reason.INVALID_RESPONSE);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("kb.search.rerank.base-url 不能为空");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 远端重排返回的候选项。
     *
     * @param index 在候选列表中的原始下标
     * @param score 模型给出的相关性得分
     */
    private record Ranked(int index, double score) {
    }

    @Override
    public String model() {
        return props.model();
    }
}
