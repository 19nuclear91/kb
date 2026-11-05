package com.zhishiku.kb.worker.telemetry;

import com.zhishiku.kb.common.monitoring.MonitoringSample;
import com.zhishiku.kb.common.monitoring.MonitoringSample.Series;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class MonitoringSnapshotConcurrencyTest {
    @ParameterizedTest
    @ValueSource(strings = {"text/plain;version=0.0.4", "application/openmetrics-text;version=1.0.0;charset=utf-8"})
    void twoScrapesAcrossPublicationKeepEachSourceAndQualityTogether(String format) throws Exception {
        var a = new Series("kb.parse.queue.depth");
        var b = new Series("kb.parse.dead_letter.depth");
        var sample = spy(new MonitoringSample("parse", Map.of(a, 0.0, b, 0.0)));
        var event = new Series("kb.embedding.events.persisted", Map.of("operation", "START"));
        var embedding = new MonitoringSample("embedding", Map.of(event, 0.0));
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pauseOnce = new AtomicBoolean(true);
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var exporter = new MonitoringSnapshotExporter();
            exporter.add(sample);
            exporter.add(embedding);
            exporter.bindTo(registry);
            sample.success(Map.of(a, 1.0, b, 10.0), 100);
            embedding.success(Map.of(event, 4.0), 100);
            doAnswer(call -> {
                var snapshot = call.callRealMethod();
                if (pauseOnce.compareAndSet(true, false)) {
                    captured.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                }
                return snapshot;
            }).when(sample).snapshot();
            var oldScrape = executor.submit(() -> registry.scrape(format));
            try {
                assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
                sample.success(Map.of(a, 2.0, b, 20.0), 200);
                embedding.failure();
                assertThat(registry.scrape(format)).contains("kb_parse_queue_depth 2.0", "kb_parse_dead_letter_depth 20.0",
                        "kb_monitoring_sample_available{source=\"parse\"} 1.0",
                        "kb_monitoring_sample_last_success_timestamp_seconds{source=\"parse\"} 200.0",
                        "kb_embedding_metrics_available 0.0", "kb_monitoring_sample_available{source=\"embedding\"} 0.0",
                        "kb_embedding_events_persisted{operation=\"START\"} 4.0");
            } finally {
                release.countDown();
            }
            assertThat(oldScrape.get(5, TimeUnit.SECONDS)).contains("kb_parse_queue_depth 1.0", "kb_parse_dead_letter_depth 10.0",
                    "kb_monitoring_sample_available{source=\"parse\"} 1.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"parse\"} 100.0");
        } finally {
            registry.close();
        }
    }
}
