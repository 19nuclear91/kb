package com.zhishiku.kb.app.conversation.monitoring;

import com.zhishiku.kb.app.aiusage.AiObservationProperties;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import com.zhishiku.kb.app.telemetry.MonitoringFeatureMetrics;
import com.zhishiku.kb.app.telemetry.MonitoringSchedule;
import com.zhishiku.kb.app.telemetry.MonitoringSnapshotExporter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettlementMetricsTest {
    @Test
    void startupTimeoutRecoveryEmptyAndFutureTimestamps() {
        var reader = mock(SettlementMetricsReader.class);
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200), Instant.ofEpochSecond(300));
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics = new SettlementMetrics(registry, reader, new MonitoringSnapshotExporter(), clock);
            assertThat(registry.scrape()).contains("kb_monitoring_sample_available{source=\"settlement\"} 0.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"settlement\"} 0.0");
            verifyNoInteractions(reader);
            when(reader.read()).thenReturn(new SettlementMetricsReader.Summary(7, 2, Instant.ofEpochSecond(10)));
            metrics.refresh();
            assertThat(registry.scrape()).contains("kb_conversation_settlement_pending 7.0", "kb_conversation_settlement_review 2.0",
                    "kb_conversation_settlement_oldest_pending_age_seconds 90.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"settlement\"} 100.0");
            when(reader.read()).thenThrow(new QueryTimeoutException("private"));
            metrics.refresh();
            assertThat(registry.scrape()).contains("kb_conversation_settlement_pending 7.0", "kb_conversation_settlement_review 2.0",
                    "kb_conversation_settlement_oldest_pending_age_seconds 90.0",
                    "kb_monitoring_sample_available{source=\"settlement\"} 0.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"settlement\"} 100.0");
            doReturn(new SettlementMetricsReader.Summary(0, 0, null)).when(reader).read();
            metrics.refresh();
            assertThat(registry.scrape()).contains("kb_conversation_settlement_pending 0.0", "kb_conversation_settlement_review 0.0",
                    "kb_conversation_settlement_oldest_pending_age_seconds 0.0",
                    "kb_monitoring_sample_available{source=\"settlement\"} 1.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"settlement\"} 200.0");
            when(reader.read()).thenReturn(new SettlementMetricsReader.Summary(1, 0, Instant.ofEpochSecond(400)));
            metrics.refresh();
            clearInvocations(reader);
            assertThat(registry.scrape()).contains("kb_conversation_settlement_pending 1.0",
                    "kb_conversation_settlement_oldest_pending_age_seconds 0.0",
                    "kb_monitoring_sample_last_success_timestamp_seconds{source=\"settlement\"} 300.0");
            verifyNoInteractions(reader);
        } finally {
            registry.close();
        }
    }

    @Test
    void bothFeaturesDisabledStillRegisterScheduleAndSampleHistoricalLedger() {
        var reader = mock(SettlementMetricsReader.class);
        when(reader.read()).thenReturn(new SettlementMetricsReader.Summary(9, 3, Instant.ofEpochSecond(100)));
        var scheduler = mock(TaskScheduler.class);
        var started = Instant.now();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class))
                .withUserConfiguration(Properties.class, SettlementMetrics.class, MonitoringSnapshotExporter.class,
                        MonitoringSchedule.class, MonitoringFeatureMetrics.class)
                .withBean(SettlementMetricsReader.class, () -> reader)
                .withBean(TaskScheduler.class, () -> scheduler)
                .withPropertyValues("kb.conversation.enabled=false", "kb.ai.observability.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MonitoringSchedule.class).getDelayMillis()).isEqualTo(30000);
                    var registry = context.getBean(PrometheusMeterRegistry.class);
                    assertThat(registry.scrape()).contains("kb_monitoring_sample_available{source=\"settlement\"} 0.0",
                            "kb_monitoring_feature_enabled{feature=\"conversation\"} 0.0",
                            "kb_monitoring_feature_enabled{feature=\"ai_observation\"} 0.0");
                    verifyNoInteractions(reader);
                    var firstRun = org.mockito.ArgumentCaptor.forClass(Instant.class);
                    verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), firstRun.capture(), eq(Duration.ofSeconds(30)));
                    assertThat(firstRun.getValue()).isBetween(started.plusSeconds(30), Instant.now().plusSeconds(30));
                    context.getBean(SettlementMetrics.class).refresh();
                    assertThat(registry.scrape()).contains("kb_conversation_settlement_pending 9.0",
                            "kb_conversation_settlement_review 3.0", "kb_monitoring_sample_available{source=\"settlement\"} 1.0");
                });
    }

    @Test
    void intervalBindingIsBounded() {
        new ApplicationContextRunner().withUserConfiguration(MonitoringSchedule.class)
                .withPropertyValues("kb.monitoring.sample-interval-ms=60000")
                .run(context -> assertThat(context.getBean(MonitoringSchedule.class).getDelayMillis()).isEqualTo(60000));
        new ApplicationContextRunner().withUserConfiguration(MonitoringSchedule.class)
                .withPropertyValues("kb.monitoring.sample-interval-ms=0").run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ConversationProperties.class, AiObservationProperties.class})
    static class Properties {
    }
}
