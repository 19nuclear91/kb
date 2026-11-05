package com.zhishiku.kb.worker.telemetry;

import com.zhishiku.kb.worker.embedding.EmbeddingMetrics;
import com.zhishiku.kb.worker.embedding.EmbeddingMetricsReader;
import com.zhishiku.kb.worker.parse.ParseTaskMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.scheduling.TaskScheduler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonitoringSamplingConfigurationTest {
    @Test
    void defaultsRegisterTwoThirtySecondSchedulesAndNeverReadOnScrape() {
        var ds = mock(DataSource.class);
        var reader = mock(EmbeddingMetricsReader.class);
        var scheduler = mock(TaskScheduler.class);
        var started = Instant.now();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class))
                .withUserConfiguration(MonitoringJdbc.class, MonitoringSchedule.class, MonitoringSnapshotExporter.class,
                        ParseTaskMetrics.class, EmbeddingMetrics.class)
                .withBean(DataSource.class, () -> ds).withBean(EmbeddingMetricsReader.class, () -> reader)
                .withBean(TaskScheduler.class, () -> scheduler).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MonitoringJdbc.class).template().getQueryTimeout()).isEqualTo(3);
                    var firstRuns = org.mockito.ArgumentCaptor.forClass(Instant.class);
                    verify(scheduler, times(2)).scheduleWithFixedDelay(any(Runnable.class), firstRuns.capture(), eq(Duration.ofSeconds(30)));
                    assertThat(firstRuns.getAllValues()).allSatisfy(value ->
                            assertThat(value).isBetween(started.plusSeconds(30), Instant.now().plusSeconds(30)));
                    assertThat(context.getBean(PrometheusMeterRegistry.class).scrape()).contains(
                            "kb_monitoring_sample_available{source=\"parse\"} 0.0",
                            "kb_monitoring_sample_available{source=\"embedding\"} 0.0");
                    verifyNoInteractions(ds, reader);
                });
    }

    @Test
    void dedicatedTemplateAppliesTimeoutAndRowLimitToJdbcStatement() throws Exception {
        var ds = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        var jdbc = new MonitoringJdbc(ds, 3).template();
        jdbc.execute((StatementCallback<Void>) value -> null);
        verify(statement).setQueryTimeout(3);
        verify(statement).setMaxRows(1);
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void overridesBindAndUnboundedConfigurationFails() {
        var runner = new ApplicationContextRunner().withUserConfiguration(MonitoringJdbc.class, MonitoringSchedule.class)
                .withBean(DataSource.class, () -> mock(DataSource.class));
        runner.withPropertyValues("kb.worker.metrics-gauge-interval-ms=60000", "kb.monitoring.query-timeout-seconds=5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MonitoringSchedule.class).getDelayMillis()).isEqualTo(60000);
                    assertThat(context.getBean(MonitoringJdbc.class).template().getQueryTimeout()).isEqualTo(5);
                });
        runner.withPropertyValues("kb.worker.metrics-gauge-interval-ms=0").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kb.monitoring.query-timeout-seconds=0").run(context -> assertThat(context).hasFailed());
    }
}
