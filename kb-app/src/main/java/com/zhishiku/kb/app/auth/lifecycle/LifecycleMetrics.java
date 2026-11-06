package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.telemetry.MonitoringSnapshotExporter;
import com.zhishiku.kb.common.monitoring.MonitoringSample;
import com.zhishiku.kb.common.monitoring.MonitoringSample.Series;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LifecycleMetrics {
    private final MonitoringSample sample = new MonitoringSample("lifecycle", values(null, false, false, null, Instant.EPOCH));
    private final LifecycleMetricsReader reader;
    private final DingTalkLifecycleSettings settings;
    private final DingTalkStreamConnection connection;
    private final LifecycleObservations observations;
    private final Clock clock;

    @Autowired
    public LifecycleMetrics(MeterRegistry registry, MonitoringSnapshotExporter exporter, LifecycleMetricsReader reader,
                            DingTalkLifecycleSettings settings, DingTalkStreamConnection connection, LifecycleObservations observations) {
        this(registry, exporter, reader, settings, connection, observations, Clock.systemUTC());
    }

    public LifecycleMetrics(MeterRegistry registry, MonitoringSnapshotExporter exporter, LifecycleMetricsReader reader,
                            DingTalkLifecycleSettings settings, DingTalkStreamConnection connection,
                            LifecycleObservations observations, Clock clock) {
        this.reader = reader;
        this.settings = settings;
        this.connection = connection;
        this.observations = observations;
        this.clock = clock;
        exporter.add(sample);
        exporter.bindTo(registry);
    }

    @Scheduled(fixedDelayString = "#{@monitoringSchedule.delayMillis}", initialDelayString = "#{@monitoringSchedule.delayMillis}")
    public synchronized void refresh() {
        try {
            var summary = reader.read();
            if (summary == null) {
                throw LifecycleFailures.unavailable();
            }
            Instant now = clock.instant();
            sample.success(values(summary, settings.enabled(), connection.connected(), connection.lastConnectedAt(), now), now.getEpochSecond());
        } catch (RuntimeException unavailable) {
            sample.failure();
            observations.dependencyFailed(LifecycleObservations.Dependency.SAMPLE);
        }
    }

    private static Map<Series, Double> values(LifecycleMetricsReader.Summary row, boolean enabled, boolean connected, Instant lastConnected, Instant now) {
        Map<Series, Double> values = new LinkedHashMap<>();
        values.put(new Series("kb.lifecycle.subscription_enabled"), enabled ? 1.0 : 0.0);
        values.put(new Series("kb.lifecycle.connected"), connected ? 1.0 : 0.0);
        if (lastConnected != null && lastConnected.isAfter(now)) {
            throw new IllegalArgumentException("Lifecycle connection clock is inconsistent");
        }
        values.put(new Series("kb.lifecycle.last_connected_timestamp_seconds"), lastConnected == null ? 0.0 : (double) lastConnected.getEpochSecond());
        values.put(new Series("kb.lifecycle.emergency_required"), enabled || row != null && row.protectionRequired() ? 1.0 : 0.0);
        values.put(new Series("kb.lifecycle.emergency_ready"), row != null && row.emergencyReady() ? 1.0 : 0.0);
        for (String status : new String[]{"UNMAPPED", "REVIEW", "FAILED", "CLEANUP"}) {
            long count = row == null ? 0 : switch (status) {
                case "UNMAPPED" -> row.unmapped();
                case "REVIEW" -> row.review();
                case "FAILED" -> row.failed();
                default -> row.cleanup();
            };
            values.put(new Series("kb.lifecycle.backlog", Map.of("status", status)), (double) count);
        }
        values.put(new Series("kb.lifecycle.oldest_record_age_seconds", Map.of("queue", "ITEM")), age(row == null ? null : row.oldestItem(), now));
        values.put(new Series("kb.lifecycle.oldest_record_age_seconds", Map.of("queue", "CLEANUP")), age(row == null ? null : row.oldestCleanup(), now));
        values.put(new Series("kb.lifecycle.output_stop_samples", Map.of("quality", "CONFIRMED")), row == null ? 0.0 : (double) row.confirmedStops());
        values.put(new Series("kb.lifecycle.output_stop_samples", Map.of("quality", "UNCERTAIN")), row == null ? 0.0 : (double) row.uncertainStops());
        values.put(new Series("kb.lifecycle.output_stop_delay_available"), row != null && row.confirmedStops() > 0 ? 1.0 : 0.0);
        values.put(new Series("kb.lifecycle.output_stop_delay_seconds", Map.of("bound", "lower")), row == null ? 0.0 : row.stopLower());
        values.put(new Series("kb.lifecycle.output_stop_delay_seconds", Map.of("bound", "upper")), row == null ? 0.0 : row.stopUpper());
        return values;
    }

    private static double age(Instant oldest, Instant now) {
        if (oldest == null) {
            return 0;
        }
        if (oldest.isAfter(now)) {
            throw new IllegalArgumentException("Lifecycle sample clock is inconsistent");
        }
        return Duration.between(oldest, now).toMillis() / 1000.0;
    }
}
