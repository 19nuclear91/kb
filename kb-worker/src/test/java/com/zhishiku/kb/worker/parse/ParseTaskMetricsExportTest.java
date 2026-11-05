package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.telemetry.HistogramConfiguration;
import com.zhishiku.kb.worker.telemetry.MonitoringJdbc;
import com.zhishiku.kb.worker.telemetry.MonitoringSnapshotExporter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParseTaskMetricsExportTest {
    @Test
    void springInjectsRegistryAndJdbcAndExportsTotalsClassesGaugesAndBuckets() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L, 2L, 90L);
        var monitoringJdbc = mock(MonitoringJdbc.class);
        when(monitoringJdbc.template()).thenReturn(jdbc);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class))
                .withUserConfiguration(ParseTaskMetrics.class, HistogramConfiguration.class, MonitoringSnapshotExporter.class)
                .withBean(MonitoringJdbc.class, () -> monitoringJdbc)
                .withPropertyValues("kb.worker.task-timeout-seconds=600")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var metrics = context.getBean(ParseTaskMetrics.class);
                    var registry = context.getBean(PrometheusMeterRegistry.class);
                    metrics.claimed(4);
                    metrics.completed();
                    metrics.retried("RETRYABLE");
                    metrics.retried(null);
                    metrics.deadLettered("PERMANENT");
                    metrics.deadLettered("UNKNOWN");
                    metrics.leaseLost();
                    metrics.alertFailed();
                    metrics.observe(metrics.startTimer());
                    metrics.refreshGauges();
                    verify(jdbc, times(3)).queryForObject(anyString(), eq(Long.class));
                    assertThat(registry.get("kb.parse.tasks.retried").counter().getId().getTags()).isEmpty();
                    assertThat(registry.get("kb.parse.tasks.dead_lettered").counter().getId().getTags()).isEmpty();
                    for (String format : new String[]{"text/plain;version=0.0.4", "application/openmetrics-text;version=1.0.0;charset=utf-8"}) {
                        assertThat(registry.scrape(format)).contains(
                                "kb_parse_tasks_claimed_total 4.0", "kb_parse_tasks_completed_total 1.0",
                                "kb_parse_tasks_retried_total 2.0", "kb_parse_tasks_dead_lettered_total 2.0",
                                "kb_parse_tasks_retried_by_class_total{failure_class=\"RETRYABLE\"} 1.0",
                                "kb_parse_tasks_retried_by_class_total{failure_class=\"UNKNOWN\"} 1.0",
                                "kb_parse_tasks_dead_lettered_by_class_total{failure_class=\"PERMANENT\"} 1.0",
                                "kb_parse_tasks_dead_lettered_by_class_total{failure_class=\"UNKNOWN\"} 1.0",
                                "kb_parse_tasks_lease_lost_total 1.0", "kb_parse_alerts_failed_total 1.0",
                                "kb_parse_queue_depth 7.0", "kb_parse_dead_letter_depth 2.0",
                                "kb_parse_queue_oldest_age_seconds 90.0", "kb_parse_task_duration_seconds_count 1",
                                "kb_parse_task_duration_seconds_bucket{le=\"600.0\"} 1");
                    }
                });
    }

    @Test
    void noArgumentTestConstructorRemainsUsableWithoutInfrastructure() {
        var metrics = new ParseTaskMetrics();
        metrics.claimed(1);
        metrics.completed();
        metrics.retried("RETRYABLE");
        metrics.deadLettered("PERMANENT");
        metrics.observe(metrics.startTimer());
        metrics.refreshGauges();
    }

    @Test
    void initialFailureSuccessPartialFailureAndRecoveryKeepFreshnessHonest() {
        var jdbc = mock(JdbcTemplate.class);
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200));
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics = new ParseTaskMetrics(registry, jdbc, new MonitoringSnapshotExporter(), clock);
            assertThat(registry.scrape()).contains("kb_monitoring_sample_available{source=\"parse\"} 0.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"parse\"} 0.0");
            verifyNoInteractions(jdbc);
            when(jdbc.queryForObject(anyString(), eq(Long.class))).thenThrow(new QueryTimeoutException("private"));
            metrics.refreshGauges();
            assertThat(registry.scrape()).contains("kb_monitoring_sample_last_success_timestamp_seconds{source=\"parse\"} 0.0");
            doReturn(7L, 2L, 90L).when(jdbc).queryForObject(anyString(), eq(Long.class));
            metrics.refreshGauges();
            assertThat(registry.scrape()).contains("kb_parse_queue_depth 7.0", "kb_parse_dead_letter_depth 2.0",
                    "kb_parse_queue_oldest_age_seconds 90.0", "kb_monitoring_sample_available{source=\"parse\"} 1.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"parse\"} 100.0");
            when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(999L)
                    .thenThrow(new QueryTimeoutException("private"));
            metrics.refreshGauges();
            assertThat(registry.scrape()).contains("kb_parse_queue_depth 7.0", "kb_parse_dead_letter_depth 2.0",
                    "kb_parse_queue_oldest_age_seconds 90.0", "kb_monitoring_sample_available{source=\"parse\"} 0.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"parse\"} 100.0");
            doReturn(3L, 1L, -9L).when(jdbc).queryForObject(anyString(), eq(Long.class));
            metrics.refreshGauges();
            clearInvocations(jdbc);
            for (int i = 0; i < 3; i++) {
                assertThat(registry.scrape()).contains("kb_parse_queue_depth 3.0", "kb_parse_queue_oldest_age_seconds 0.0",
                        "kb_monitoring_sample_available{source=\"parse\"} 1.0",
                        "kb_monitoring_sample_last_success_timestamp_seconds{source=\"parse\"} 200.0");
            }
            verifyNoInteractions(jdbc);
        } finally {
            registry.close();
        }
    }

    @Test
    void scrapeDuringPartialReadSeesOnlyPreviousCompleteSnapshot() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var metrics = new ParseTaskMetrics(registry, jdbc);
            metrics.refreshGauges();
            doAnswer(call -> {
                if (call.getArgument(0, String.class).contains("dead_letter")) {
                    reached.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                }
                return 2L;
            }).when(jdbc).queryForObject(anyString(), eq(Long.class));
            var sampling = executor.submit(metrics::refreshGauges);
            try {
                assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(registry.scrape()).contains("kb_parse_queue_depth 1.0", "kb_parse_dead_letter_depth 1.0",
                        "kb_parse_queue_oldest_age_seconds 1.0", "kb_monitoring_sample_available{source=\"parse\"} 1.0");
            } finally {
                release.countDown();
            }
            sampling.get(5, TimeUnit.SECONDS);
            assertThat(registry.scrape()).contains("kb_parse_queue_depth 2.0", "kb_parse_dead_letter_depth 2.0",
                    "kb_parse_queue_oldest_age_seconds 2.0");
        } finally {
            registry.close();
        }
    }
}
