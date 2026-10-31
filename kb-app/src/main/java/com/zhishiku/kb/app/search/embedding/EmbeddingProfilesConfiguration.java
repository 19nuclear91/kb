package com.zhishiku.kb.app.search.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import com.zhishiku.kb.common.embedding.RemoteProfileEmbeddingClient;
import com.zhishiku.kb.common.embedding.RemoteEmbeddingTransport;
import com.zhishiku.kb.common.embedding.CallBudget;
import com.zhishiku.kb.app.search.execution.SearchHttpTransport;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class EmbeddingProfilesConfiguration {

    @Bean
    public EmbeddingClientRegistry embeddingClientRegistry(EmbeddingProfilesProperties properties,
                                                           RestClient.Builder builder, SearchHttpTransport transport,
                                                           SearchLimitsProperties limits) {
        return new EmbeddingClientRegistry(properties.profiles(), EmbeddingRole.QUERY, definition -> {
            if ("local".equals(definition.profile().provider())) {
                return new LocalProfileEmbeddingClient(definition, EmbeddingRole.QUERY);
            }
            var connection = definition.connection();
            var timeout = Duration.ofSeconds(connection.timeoutSeconds());
            var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .connectTimeout(timeout).build());
            requestFactory.setReadTimeout(timeout);
            String endpoint = connection.endpoint().replaceAll("/+$", "");
            var configured = builder.clone().baseUrl(endpoint).requestFactory(requestFactory);
            if (connection.apiKey() != null && !connection.apiKey().isBlank()) {
                configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + connection.apiKey());
            }
            RestClient client = configured.build();
            return new RemoteProfileEmbeddingClient(definition, EmbeddingRole.QUERY, new RemoteEmbeddingTransport() {
                @Override
                public Map<String, Object> post(Map<String, Object> body) {
                    return client.post().uri("/embeddings").body(body).retrieve()
                            .body(new ParameterizedTypeReference<>() { });
                }

                @Override
                public Map<String, Object> post(Map<String, Object> body, CallBudget budget) {
                    return transport.post(URI.create(endpoint + "/embeddings"), connection.apiKey(), body, budget,
                            limits.maxRerankRequestBytes(), connection.timeoutSeconds());
                }
            });
        });
    }
}
