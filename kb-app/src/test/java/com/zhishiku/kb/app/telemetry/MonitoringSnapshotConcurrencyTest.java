package com.zhishiku.kb.app.telemetry;

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
    void twoScrapesAndFailurePublicationCannotMixSettlementValuesOrQuality(String format) throws Exception {
        var pending = new Series("kb.conversation.settlement.pending");
        var review = new Series("kb.conversation.settlement.review");
        var age = new Series("kb.conversation.settlement.oldest_pending_age_seconds");
        var sample = spy(new MonitoringSample("settlement", Map.of(pending, 0.0, review, 0.0, age, 0.0)));
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pauseOnce = new AtomicBoolean(true);
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var exporter = new MonitoringSnapshotExporter();
            exporter.add(sample);
            exporter.bindTo(registry);
            sample.success(Map.of(pending, 1.0, review, 10.0, age, 90.0), 100);
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
                sample.success(Map.of(pending, 2.0, review, 20.0, age, 190.0), 200);
                sample.failure();
                assertThat(registry.scrape(format)).contains("kb_conversation_settlement_pending 2.0",
                        "kb_conversation_settlement_review 20.0", "kb_conversation_settlement_oldest_pending_age_seconds 190.0",
                        "kb_monitoring_sample_available{source=\"settlement\"} 0.0",
                        "kb_monitoring_sample_last_success_timestamp_seconds{source=\"settlement\"} 200.0");
            } finally {
                release.countDown();
            }
            assertThat(oldScrape.get(5, TimeUnit.SECONDS)).contains("kb_conversation_settlement_pending 1.0",
                    "kb_conversation_settlement_review 10.0", "kb_conversation_settlement_oldest_pending_age_seconds 90.0",
                    "kb_monitoring_sample_available{source=\"settlement\"} 1.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"settlement\"} 100.0");
        } finally {
            registry.close();
        }
    }
}
