package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.monitoring.MonitoringSample;
import com.zhishiku.kb.common.monitoring.MonitoringSample.Series;
import com.zhishiku.kb.worker.telemetry.MonitoringSnapshotExporter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddingMetrics {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingMetrics.class);
    private static final List<String> STATES = List.of("UNINITIALIZED", "ACTIVE", "QUIESCING", "REBUILDING",
            "VERIFYING", "BLOCKED", "UNKNOWN");
    private static final List<String> ERRORS = List.of("NONE", "MODEL_UNAVAILABLE", "INVALID_RESPONSE", "LEASE_LOST",
            "CONTENT_CHANGED", "MISSING_CHUNKS", "COVERAGE_INVALID", "READINESS_EXPIRED", "RETRY_EXHAUSTED",
            "DATABASE_ERROR", "INTERNAL_ERROR");
    private static final List<String> OPERATIONS = List.of("START", "RESUME", "BLOCKED", "SUCCEEDED");
    private final EmbeddingMetricsReader reader;
    private final Clock clock;
    private final MonitoringSample snapshot;
    private String previous;

    @Autowired
    public EmbeddingMetrics(MeterRegistry registry, EmbeddingMetricsReader reader, MonitoringSnapshotExporter exporter) {
        this(registry, reader, exporter, Clock.systemUTC());
    }

    public EmbeddingMetrics(MeterRegistry registry, EmbeddingMetricsReader reader,
                            MonitoringSnapshotExporter exporter, Clock clock) {
        this.reader = reader;
        this.clock = clock;
        Map<Series, Double> initial = new LinkedHashMap<>();
        STATES.forEach(value -> initial.put(series("mode", "state", value), 0.0));
        ERRORS.forEach(value -> initial.put(series("error", "category", value), 0.0));
        OPERATIONS.forEach(value -> initial.put(series("events.persisted", "operation", value), 0.0));
        List.of("total", "succeeded", "skipped", "remaining").forEach(value ->
                initial.put(series("progress", "result", value), 0.0));
        snapshot = new MonitoringSample("embedding", initial);
        exporter.add(snapshot);
        exporter.bindTo(registry);
    }

    private static Series series(String name, String label, String value) {
        return new Series("kb.embedding." + name, Map.of(label, value));
    }

    @Scheduled(fixedDelayString = "#{@monitoringSchedule.delayMillis}",
            initialDelayString = "#{@monitoringSchedule.delayMillis}")
    public synchronized void refresh() {
        try {
            var state = reader.state();
            if (state == null || state.mode() == null) {
                throw new IllegalStateException("Embedding state unavailable");
            }
            String mode = STATES.contains(state.mode()) ? state.mode() : "UNKNOWN";
            String error = state.error() == null ? "NONE"
                    : ERRORS.contains(state.error()) ? state.error() : "INTERNAL_ERROR";
            Map<Series, Double> values = new LinkedHashMap<>();
            STATES.forEach(value -> values.put(series("mode", "state", value), value.equals(mode) ? 1.0 : 0.0));
            ERRORS.forEach(value -> values.put(series("error", "category", value), value.equals(error) ? 1.0 : 0.0));
            values.put(series("progress", "result", "total"), (double) state.total());
            values.put(series("progress", "result", "succeeded"), (double) state.succeeded());
            values.put(series("progress", "result", "skipped"), (double) state.skipped());
            values.put(series("progress", "result", "remaining"),
                    (double) Math.max(0, state.total() - state.succeeded() - state.skipped()));
            // 持久事件是数据库 Gauge，不是进程 Counter；失败不能发布部分计数。
            for (String operation : OPERATIONS) {
                values.put(series("events.persisted", "operation", operation), (double) reader.events(operation));
            }
            snapshot.success(values, clock.instant().getEpochSecond());
            String signature = mode + ":" + error;
            if (!signature.equals(previous)) {
                log.info("embedding state={} error={}", mode, error);
                previous = signature;
            }
        } catch (RuntimeException unavailable) {
            snapshot.failure();
            log.warn("embedding metrics unavailable");
        }
    }
}
