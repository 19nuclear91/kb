package com.zhishiku.kb.worker.embedding.execution;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration(proxyBeanMethods = false)
public class WorkerEmbeddingConfiguration {
    @Bean
    WorkerEmbeddingSettings workerEmbeddingSettings(ConfigurableEnvironment environment) {
        return WorkerEmbeddingSettings.from(environment);
    }

    @Bean
    WorkerExecutions workerExecutions(WorkerEmbeddingSettings settings) {
        return new WorkerExecutions(settings);
    }

    @Bean
    WorkerEmbeddingMetrics workerEmbeddingMetrics(WorkerExecutions executions, MeterRegistry registry) {
        return new WorkerEmbeddingMetrics(executions, registry);
    }

    @Bean
    WorkerEmbeddingJson workerEmbeddingJson(WorkerEmbeddingSettings settings) {
        return new WorkerEmbeddingJson(settings);
    }

    @Bean
    WorkerEmbeddingTransport workerEmbeddingTransport(WorkerEmbeddingSettings settings, WorkerEmbeddingJson json,
                                                       WorkerEmbeddingMetrics metrics) {
        return new WorkerEmbeddingTransport(settings, json, metrics);
    }
}
