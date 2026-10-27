package com.zhishiku.kb.app.search.rerank;

import com.zhishiku.kb.app.search.model.ChunkHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
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
    private final RestClient client;

    public OpenAiCompatibleRerankClient(RerankProperties props, RestClient.Builder builder) {
        this.props = props;
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        var configured = builder.baseUrl(stripTrailingSlash(props.baseUrl())).requestFactory(requestFactory);
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        }
        this.client = configured.build();
    }

    @Override
    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates, int topK) {
        if (candidates.isEmpty() || topK <= 0) return List.of();
        List<String> documents = candidates.stream().map(ChunkHit::content).toList();
        Map<String, Object> response = client.post().uri("/rerank")
                .body(Map.of("model", props.model(), "query", query,
                        "documents", documents, "top_n", topK))
                .retrieve().body(new ParameterizedTypeReference<>() {
                });
        Object raw = response == null ? null : response.get("results");
        if (!(raw instanceof List<?> results)) return candidates.subList(0, Math.min(topK, candidates.size()));
        List<Ranked> ranked = new ArrayList<>();
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> map)
                    || !(map.get("index") instanceof Number index)) continue;
            double score = map.get("relevance_score") instanceof Number n ? n.doubleValue() : 0;
            int i = index.intValue();
            if (i >= 0 && i < candidates.size()) ranked.add(new Ranked(i, score));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        if (ranked.isEmpty()) return candidates.subList(0, Math.min(topK, candidates.size()));
        return ranked.stream().limit(topK).map(r -> candidates.get(r.index())).toList();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("kb.search.rerank.base-url 不能为空");
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
