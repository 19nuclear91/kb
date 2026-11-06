package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.telemetry.MonitoringSnapshotExporter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LifecycleMetricsTest {
    @Test
    void readerAggregatesOnlyLinkedConfirmedIntervalsAndRejectsTruncatedSample() {
        var management = mock(com.zhishiku.kb.app.auth.service.ManagementInvariant.class);
        var actual = new LifecycleMetricsReader(mock(javax.sql.DataSource.class), management, 3);
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "jdbc", jdbc);
        when(jdbc.queryForObject(LifecycleMetricsReader.SQL, LifecycleMetricsReader.MAPPER))
                .thenReturn(new LifecycleMetricsReader.Summary(0, 0, 0, 0, null, null, true, false));
        when(jdbc.query(LifecycleMetricsReader.STOP_SQL, LifecycleMetricsReader.STOP_MAPPER)).thenReturn(java.util.List.of(
                new OutputStopWindow(OutputStopWindow.Quality.CONFIRMED, 3, 5),
                new OutputStopWindow(OutputStopWindow.Quality.UNCERTAIN, 0, 0),
                new OutputStopWindow(OutputStopWindow.Quality.NOT_LINKED, 0, 0),
                new OutputStopWindow(OutputStopWindow.Quality.DEFERRED, 0, 0)));
        var sampled = actual.read();
        assertThat(sampled.confirmedStops()).isEqualTo(1);
        assertThat(sampled.uncertainStops()).isEqualTo(1);
        assertThat(sampled.stopLower()).isEqualTo(3);
        assertThat(sampled.stopUpper()).isEqualTo(5);
        when(jdbc.query(LifecycleMetricsReader.STOP_SQL, LifecycleMetricsReader.STOP_MAPPER)).thenReturn(
                java.util.Collections.nCopies(1001, new OutputStopWindow(OutputStopWindow.Quality.CONFIRMED, 3, 5)));
        assertThatThrownBy(actual::read).isInstanceOf(RuntimeException.class);
    }
    private final Instant now = Instant.parse("2026-01-01T00:10:00Z");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LifecycleMetricsReader reader = mock(LifecycleMetricsReader.class);
    private final LifecycleObservations observations = new LifecycleObservations(registry);
    private final LifecycleMetrics metrics = new LifecycleMetrics(registry, new MonitoringSnapshotExporter(), reader,
            DingTalkLifecycleSettingsTest.configured(false), mock(DingTalkStreamConnection.class), observations,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void neverSampledIsUnknownAndFailureKeepsLastGoodValueWithInvalidQuality() {
        assertThat(gauge("kb.monitoring.sample.available")).isZero();
        assertThat(gauge("kb.monitoring.sample.last_success_timestamp_seconds")).isZero();
        when(reader.read()).thenReturn(new LifecycleMetricsReader.Summary(2, 1, 3, 1,
                now.minusSeconds(90), now.minusSeconds(60), true, false));
        metrics.refresh();
        assertThat(gauge("kb.monitoring.sample.available")).isEqualTo(1);
        assertThat(registry.get("kb.lifecycle.backlog").tag("status", "UNMAPPED").gauge().value()).isEqualTo(2);
        assertThat(registry.get("kb.lifecycle.oldest_record_age_seconds").tag("queue", "ITEM").gauge().value()).isEqualTo(90);
        assertThat(gauge("kb.lifecycle.subscription_enabled")).isZero();
        assertThat(gauge("kb.lifecycle.emergency_required")).isEqualTo(1);
        when(reader.read()).thenThrow(new IllegalStateException("database private employee"));
        metrics.refresh();
        assertThat(gauge("kb.monitoring.sample.available")).isZero();
        assertThat(gauge("kb.monitoring.sample.last_success_timestamp_seconds")).isEqualTo(now.getEpochSecond());
        assertThat(registry.get("kb.lifecycle.backlog").tag("status", "UNMAPPED").gauge().value()).isEqualTo(2);
        assertThat(registry.get("kb.lifecycle.dependency_failures").tag("operation", "SAMPLE").counter().count()).isEqualTo(1);
        assertThat(registry.find("kb.lifecycle.access_barrier_delay").timer()).isNull();
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isIn("source", "status", "queue", "operation", "reason", "outcome", "quality", "bound");
            if (tag.getKey().equals("reason")) {
                assertThat(DingTalkInputRejected.Reason.valueOf(tag.getValue())).isNotNull();
            } else {
                assertThat(tag.getValue()).isIn("dingtalk", "lifecycle", "UNMAPPED", "REVIEW", "FAILED", "CLEANUP", "ITEM", "SAMPLE",
                        "COMMITTED", "DUPLICATE", "CONFLICT", "CONFIRMED", "UNCERTAIN", "lower", "upper");
            }
        }));
    }

    @Test
    void emptyIsKnownZeroOnlyAfterSuccessfulSampleAndFutureRecordIsUnknown() {
        when(reader.read()).thenReturn(new LifecycleMetricsReader.Summary(0, 0, 0, 0, null, null, false, false));
        metrics.refresh();
        assertThat(gauge("kb.monitoring.sample.available")).isEqualTo(1);
        assertThat(registry.get("kb.lifecycle.oldest_record_age_seconds").tag("queue", "ITEM").gauge().value()).isZero();
        assertThat(registry.find("kb.lifecycle.access_barrier_delay").timer()).isNull();
        assertThat(gauge("kb.lifecycle.output_stop_delay_available")).isZero();
        assertThat(registry.get("kb.lifecycle.output_stop_samples").tag("quality", "CONFIRMED").gauge().value()).isZero();
        when(reader.read()).thenReturn(new LifecycleMetricsReader.Summary(1, 0, 0, 0, now.plusSeconds(1), null, true, true));
        metrics.refresh();
        assertThat(gauge("kb.monitoring.sample.available")).isZero();
        assertThatThrownBy(() -> new LifecycleMetricsReader.Summary(1, 0, 0, 0, null, null, false, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correlatedBoundsExposeQualityAndDoNotCountUnknownAsCompleted() {
        when(reader.read()).thenReturn(new LifecycleMetricsReader.Summary(0, 0, 0, 0, null, null, true, true, 2, 1, 29, 31));
        metrics.refresh();
        assertThat(gauge("kb.lifecycle.output_stop_delay_available")).isEqualTo(1);
        assertThat(registry.get("kb.lifecycle.output_stop_delay_seconds").tag("bound", "lower").gauge().value()).isEqualTo(29);
        assertThat(registry.get("kb.lifecycle.output_stop_delay_seconds").tag("bound", "upper").gauge().value()).isEqualTo(31);
        assertThat(registry.get("kb.lifecycle.output_stop_samples").tag("quality", "UNCERTAIN").gauge().value()).isEqualTo(1);
        when(reader.read()).thenReturn(new LifecycleMetricsReader.Summary(0, 0, 0, 0, null, null, true, true, 0, 1, 0, 0));
        metrics.refresh();
        assertThat(gauge("kb.lifecycle.output_stop_delay_available")).isZero();
    }

    @Test
    void rowProjectionPreservesMissingTimestampAndRejectsInconsistentCount() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("unmapped")).thenReturn(1L);
        assertThatThrownBy(() -> LifecycleMetricsReader.MAPPER.mapRow(row, 0)).isInstanceOf(IllegalArgumentException.class);
        when(row.getTimestamp("oldest_item")).thenReturn(Timestamp.from(now));
        assertThat(LifecycleMetricsReader.MAPPER.mapRow(row, 0).oldestItem()).isEqualTo(now);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
