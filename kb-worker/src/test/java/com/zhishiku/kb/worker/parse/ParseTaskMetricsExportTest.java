package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.telemetry.HistogramConfiguration;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParseTaskMetricsExportTest {
    @Test
    void springInjectsRegistryAndJdbcAndExportsTotalsClassesGaugesAndBuckets() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L, 2L, 90L);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class))
                .withUserConfiguration(ParseTaskMetrics.class, HistogramConfiguration.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
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
}
