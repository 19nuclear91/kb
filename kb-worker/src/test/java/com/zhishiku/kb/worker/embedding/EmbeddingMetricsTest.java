package com.zhishiku.kb.worker.embedding;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.worker.telemetry.MonitoringSnapshotExporter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import static org.mockito.Mockito.doAnswer;
class EmbeddingMetricsTest {
    @Test
    void fixedLabelsPersistedEventCountsAndSanitizedLogsDoNotGrowWithRuns() {
        var registry = new SimpleMeterRegistry();
        var reader = mock(EmbeddingMetricsReader.class);
        var id = UUID.randomUUID();
        when(reader.state()).thenReturn(new EmbeddingMetricsReader.State("BLOCKED", "private body api-key", 10, 4, 1));
        when(reader.events(anyString())).thenReturn(2L);
        var logger = (Logger) LoggerFactory.getLogger(EmbeddingMetrics.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var metrics = new EmbeddingMetrics(registry, reader, new MonitoringSnapshotExporter());
            int size = registry.getMeters().size();
            metrics.refresh();
            metrics.refresh();
            assertThat(registry.getMeters()).hasSize(size);
            assertThat(registry.get("kb.embedding.mode").tag("state", "BLOCKED").gauge().value()).isEqualTo(1);
            assertThat(registry.get("kb.embedding.progress").tag("result", "remaining").gauge().value()).isEqualTo(5);
            assertThat(registry.get("kb.embedding.events.persisted").tag("operation", "START").gauge().value()).isEqualTo(2);
            assertThat(registry.get("kb.embedding.error").tag("category", "INTERNAL_ERROR").gauge().value()).isEqualTo(1);
            when(reader.state()).thenThrow(new IllegalStateException("private body api-key"));
            metrics.refresh();
            assertThat(registry.get("kb.embedding.metrics.available").gauge().value()).isZero();
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private", "api-key", id.toString());
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                    .doesNotContain("private", id.toString(), "profileId", "chunkId"));
        } finally {
            logger.detachAppender(appender);
            registry.close();
        }
    }

    @Test
    void fullSnapshotSurvivesStateProgressAndLateEventFailuresAndRecovers() {
        var reader = mock(EmbeddingMetricsReader.class);
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200));
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics = new EmbeddingMetrics(registry, reader, new MonitoringSnapshotExporter(), clock);
            assertThat(registry.scrape()).contains("kb_embedding_metrics_available 0.0",
                    "kb_monitoring_sample_available{source=\"embedding\"} 0.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"embedding\"} 0.0");
            verifyNoInteractions(reader);
            when(reader.state()).thenReturn(new EmbeddingMetricsReader.State("ACTIVE", null, 10, 4, 1));
            when(reader.events(anyString())).thenReturn(3L);
            metrics.refresh();
            assertThat(registry.scrape()).contains("# TYPE kb_embedding_events_persisted gauge",
                    "kb_embedding_events_persisted{operation=\"START\"} 3.0", "kb_embedding_metrics_available 1.0",
                    "kb_monitoring_sample_available{source=\"embedding\"} 1.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"embedding\"} 100.0");
            when(reader.state()).thenThrow(new QueryTimeoutException("private"));
            metrics.refresh();
            assertOldUnavailable(registry);
            doReturn(new EmbeddingMetricsReader.State("BLOCKED", "LEASE_LOST", 100, 50, 1)).when(reader).state();
            when(reader.events(anyString())).thenReturn(999L).thenThrow(new QueryTimeoutException("private"));
            metrics.refresh();
            assertOldUnavailable(registry);
            doReturn(8L).when(reader).events(anyString());
            metrics.refresh();
            clearInvocations(reader);
            for (String format : new String[]{"text/plain;version=0.0.4", "application/openmetrics-text;version=1.0.0;charset=utf-8"}) {
                assertThat(registry.scrape(format)).contains("kb_embedding_mode{state=\"BLOCKED\"} 1.0",
                        "kb_embedding_progress{result=\"remaining\"} 49.0", "kb_embedding_metrics_available 1.0",
                        "kb_monitoring_sample_available{source=\"embedding\"} 1.0",
                        "kb_monitoring_sample_last_success_timestamp_seconds{source=\"embedding\"} 200.0")
                        .doesNotContain("kb_embedding_events_persisted_total", "private");
            }
            verifyNoInteractions(reader);
        } finally {
            registry.close();
        }
    }
    @Test
    void scrapeDuringLateEventReadDoesNotPublishNewStateOrProgressEarly() throws Exception {
        var reader = mock(EmbeddingMetricsReader.class);
        when(reader.state()).thenReturn(new EmbeddingMetricsReader.State("ACTIVE", null, 10, 4, 1));
        when(reader.events(anyString())).thenReturn(3L);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var metrics = new EmbeddingMetrics(registry, reader, new MonitoringSnapshotExporter());
            metrics.refresh();
            when(reader.state()).thenReturn(new EmbeddingMetricsReader.State("BLOCKED", "LEASE_LOST", 100, 50, 1));
            doAnswer(call -> {
                if (call.getArgument(0).equals("RESUME")) {
                    reached.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                }
                return 8L;
            }).when(reader).events(anyString());
            var sampling = executor.submit(metrics::refresh);
            try {
                assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(registry.scrape()).contains("kb_embedding_mode{state=\"ACTIVE\"} 1.0",
                        "kb_embedding_progress{result=\"remaining\"} 5.0", "kb_embedding_events_persisted{operation=\"START\"} 3.0",
                        "kb_embedding_metrics_available 1.0", "kb_monitoring_sample_available{source=\"embedding\"} 1.0");
            } finally {
                release.countDown();
            }
            sampling.get(5, TimeUnit.SECONDS);
            assertThat(registry.scrape()).contains("kb_embedding_mode{state=\"BLOCKED\"} 1.0",
                    "kb_embedding_progress{result=\"remaining\"} 49.0", "kb_embedding_events_persisted{operation=\"START\"} 8.0");
        } finally {
            registry.close();
        }
    }


    private void assertOldUnavailable(PrometheusMeterRegistry registry) {
        assertThat(registry.scrape()).contains("kb_embedding_mode{state=\"ACTIVE\"} 1.0",
                "kb_embedding_progress{result=\"remaining\"} 5.0", "kb_embedding_events_persisted{operation=\"START\"} 3.0",
                "kb_embedding_metrics_available 0.0", "kb_monitoring_sample_available{source=\"embedding\"} 0.0",
                "kb_monitoring_sample_last_success_timestamp_seconds{source=\"embedding\"} 100.0");
    }
}
