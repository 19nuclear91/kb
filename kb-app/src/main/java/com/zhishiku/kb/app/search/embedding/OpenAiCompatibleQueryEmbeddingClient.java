package com.zhishiku.kb.app.search.embedding;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 可选的 OpenAI-compatible 查询 embedding 适配器；仅在 provider=remote 时发起请求。
 */
public class OpenAiCompatibleQueryEmbeddingClient implements QueryEmbeddingClient {

    private final QueryEmbeddingProperties props;
    private final RestClient client;

    public OpenAiCompatibleQueryEmbeddingClient(QueryEmbeddingProperties props, RestClient.Builder builder) {
        this.props = props;
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        var configured = RestClient.builder().baseUrl(stripTrailingSlash(props.baseUrl())).requestFactory(requestFactory);
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        }
        this.client = configured.build();
    }

    @Override
    public float[] embed(String text) {
        Map<String, Object> response = client.post()
                .uri("/embeddings")
                .body(Map.of("model", props.model(), "input", List.of(text),
                        "dimensions", props.dimension()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> items) || items.isEmpty() || !(items.get(0) instanceof Map<?, ?> item)) {
            throw new IllegalStateException("embedding 响应缺少 data");
        }
        Object raw = item.get("embedding");
        if (!(raw instanceof List<?> numbers) || numbers.size() != dimension()) {
            throw new IllegalStateException("embedding 维度不匹配，期望 " + dimension());
        }
        float[] result = new float[numbers.size()];
        for (int i = 0; i < result.length; i++) {
            if (!(numbers.get(i) instanceof Number number)) {
                throw new IllegalStateException("embedding 含非数字值");
            }
            result[i] = number.floatValue();
        }
        return result;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("kb.search.embedding.base-url 不能为空");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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
