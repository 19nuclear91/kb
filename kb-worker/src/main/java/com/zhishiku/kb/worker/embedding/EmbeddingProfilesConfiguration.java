package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import com.zhishiku.kb.common.embedding.RemoteProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingBatchPlanner;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingJson;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EmbeddingProfilesConfiguration {

    @Bean
    public EmbeddingClientRegistry embeddingClientRegistry(EmbeddingProfilesProperties properties,
                                                            WorkerEmbeddingSettings settings,
                                                            WorkerEmbeddingJson json,
                                                            WorkerEmbeddingTransport transport) {
        return new EmbeddingClientRegistry(properties.profiles(), EmbeddingRole.DOCUMENT, definition -> {
            if ("local".equals(definition.profile().provider())) {
                return transport.observe(new LocalProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT));
            }
            return transport.observe(new RemoteProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT,
                    transport.forProfile(definition), new WorkerEmbeddingBatchPlanner(settings, json)));
        });
    }
}
