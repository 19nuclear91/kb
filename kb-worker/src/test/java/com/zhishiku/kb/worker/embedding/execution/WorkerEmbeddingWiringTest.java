package com.zhishiku.kb.worker.embedding.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.worker.embedding.EmbeddingProfilesConfiguration;
import com.zhishiku.kb.worker.embedding.EmbeddingProfilesProperties;
import com.zhishiku.kb.worker.telemetry.InMemoryTracing;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerEmbeddingWiringTest {
    @Test
    void allPurposesUseBoundedTransportAndRejectDefaultNetworkEntry() throws Exception {
        var settings = WorkerEmbeddingSettings.defaults();
        var json = new WorkerEmbeddingJson(settings);
        byte[] bytes = new ObjectMapper().writeValueAsBytes(Map.of("data", List.of(EmbeddingTestSupport.item(0, 1))));
        var sessions = new ArrayList<MemoryEmbeddingSession>();
        var transport = new WorkerEmbeddingTransport(settings, json, () -> {
            var session = new MemoryEmbeddingSession(200, bytes, Map.of());
            sessions.add(session);
            return session;
        });
        var properties = new EmbeddingProfilesProperties(List.of(EmbeddingTestSupport.definition(16)));
        var client = new EmbeddingProfilesConfiguration().embeddingClientRegistry(properties, settings, json, transport)
                .clients().iterator().next();
        var tracing = new InMemoryTracing();
        var root = tracing.tracer.nextSpan().name("embedding.test").start();
        try (var scope = tracing.tracer.withSpan(root);
             var baggage = tracing.tracer.createBaggageInScope("requestId", "private-context")) {
            assertThatThrownBy(() -> client.embed(List.of("document"))).isInstanceOf(IllegalStateException.class);
            assertThat(sessions).isEmpty();
            for (var purpose : WorkerExecution.Purpose.values()) {
                var execution = new WorkerExecution(purpose, settings.maxResponseBytes(), System::nanoTime,
                        (action, nanos) -> () -> { }, Runnable::run);
                execution.begin(Duration.ofSeconds(10));
                assertThat(client.embed(List.of("document"), execution).size()).isOne();
            }
            assertThat(sessions).hasSize(3);
            for (var session : sessions) {
                assertThat(session.requests).hasSize(1);
                var request = session.requests.getFirst();
                assertThat(request.headers().map()).containsOnlyKeys("Authorization", "Content-Type", "Accept");
                assertThat(request.headers().firstValue("Authorization")).contains("Bearer test-key");
                assertThat(request.timeout().orElseThrow()).isLessThanOrEqualTo(Duration.ofSeconds(10));
                assertThat(session.shutdown).isTrue();
            }
            assertThat(tracing.tracer.currentSpan().context().traceId()).isEqualTo(root.context().traceId());
        } finally {
            root.end();
            tracing.close();
        }
    }

    @Test
    void retryPolicyIsAppliedAtEarliestEnvironmentStage() {
        var policy = new EmbeddingHttpPolicy();
        policy.postProcessEnvironment(new MockEnvironment(), null);
        assertThat(policy.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(System.getProperty("jdk.httpclient.disableRetryConnect")).isEqualTo("true");
        assertThat(System.getProperty("jdk.httpclient.enableAllMethodRetry")).isEqualTo("false");
    }
}
