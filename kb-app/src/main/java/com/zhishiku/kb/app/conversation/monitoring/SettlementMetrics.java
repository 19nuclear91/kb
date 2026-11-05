package com.zhishiku.kb.app.conversation.monitoring;

import com.zhishiku.kb.app.telemetry.MonitoringSnapshotExporter;
import com.zhishiku.kb.common.monitoring.MonitoringSample;
import com.zhishiku.kb.common.monitoring.MonitoringSample.Series;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** 始终装配，关闭新会话或观测采集不能抹掉遗留结算。 */
@Component
public class SettlementMetrics {
    private static final Series PENDING = new Series("kb.conversation.settlement.pending");
    private static final Series REVIEW = new Series("kb.conversation.settlement.review");
    private static final Series OLDEST = new Series("kb.conversation.settlement.oldest_pending_age_seconds");
    private final MonitoringSample snapshot = new MonitoringSample("settlement",
            Map.of(PENDING, 0.0, REVIEW, 0.0, OLDEST, 0.0));
    private final SettlementMetricsReader reader;
    private final Clock clock;

    @Autowired
    public SettlementMetrics(MeterRegistry registry, SettlementMetricsReader reader, MonitoringSnapshotExporter exporter) {
        this(registry, reader, exporter, Clock.systemUTC());
    }

    public SettlementMetrics(MeterRegistry registry, SettlementMetricsReader reader,
                             MonitoringSnapshotExporter exporter, Clock clock) {
        this.reader = reader;
        this.clock = clock;
        exporter.add(snapshot);
        exporter.bindTo(registry);
    }

    @Scheduled(fixedDelayString = "#{@monitoringSchedule.delayMillis}",
            initialDelayString = "#{@monitoringSchedule.delayMillis}")
    public synchronized void refresh() {
        try {
            var summary = reader.read();
            var now = clock.instant();
            long age = summary.pending() == 0 ? 0
                    : Math.max(0, Duration.between(summary.oldestFinalizedAt(), now).getSeconds());
            snapshot.success(Map.of(PENDING, (double) summary.pending(), REVIEW, (double) summary.review(),
                    OLDEST, (double) age), now.getEpochSecond());
        } catch (RuntimeException unavailable) {
            snapshot.failure();
        }
    }
}
