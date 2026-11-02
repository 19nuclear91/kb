package com.zhishiku.kb.worker.embedding;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 调用 OpenAI-compatible POST /embeddings 接口。
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties props;
    private final RestClient client;

    public OpenAiCompatibleEmbeddingClient(EmbeddingProperties props, RestClient.Builder builder) {
        this.props = props;
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        var b = RestClient.builder().baseUrl(stripTrailingSlash(props.baseUrl()))
                .requestFactory(requestFactory);
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        }
        this.client = b.build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += props.batchSize()) {
            int end = Math.min(texts.size(), start + props.batchSize());
            result.addAll(embedBatch(texts.subList(start, end)));
        }
        return result;
    }

    private List<float[]> embedBatch(List<String> texts) {
        var body = Map.of("model", props.model(), "input", texts,
                "dimensions", props.dimension());
        Map<String, Object> response = client.post()
                .uri("/embeddings")
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> items)) {
            throw new IllegalStateException("embedding 响应缺少 data");
        }
        List<float[]> vectors = items.stream()
                .filter(Map.class::isInstance)
                .map(o -> toVector((Map<?, ?>) o))
                .sorted(Comparator.comparingInt(v -> v.index))
                .map(v -> v.values)
                .toList();
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("embedding 返回数量不匹配，期望 " + texts.size());
        }
        return vectors;
    }

    private Vector toVector(Map<?, ?> item) {
        Object raw = item.get("embedding");
        if (!(raw instanceof List<?> numbers) || numbers.size() != dimension()) {
            throw new IllegalStateException("embedding 维度不匹配，期望 " + dimension());
        }
        float[] values = new float[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            Object n = numbers.get(i);
            if (!(n instanceof Number number)) throw new IllegalStateException("embedding 含非数字值");
            values[i] = number.floatValue();
        }
        Object index = item.get("index");
        return new Vector(index instanceof Number n ? n.intValue() : 0, values);
    }

    /**
     * 单条 embedding 数据项。
     *
     * @param index  在本次请求批次中的位置
     * @param values 向量分量
     */
    private record Vector(int index, float[] values) {
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("kb.embedding.base-url 不能为空");
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public int dimension() {
        return props.dimension();
    }
}
