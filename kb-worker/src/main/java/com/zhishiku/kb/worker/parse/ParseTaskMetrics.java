package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.monitoring.MonitoringSample;
import com.zhishiku.kb.common.monitoring.MonitoringSample.Series;
import com.zhishiku.kb.worker.telemetry.MonitoringJdbc;
import com.zhishiku.kb.worker.telemetry.MonitoringSnapshotExporter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;

/**
 * 解析任务低基数指标；任务和版本 ID 永远不会成为标签。
 */
@Component
public class ParseTaskMetrics {

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final Counter claimed;
    private final Counter completed;
    private final Counter retried;
    private final Counter deadLettered;
    private final Counter leaseLost;
    private final Counter alertFailed;
    private static final Series BACKLOG = new Series("kb.parse.queue.depth");
    private static final Series DEAD_LETTERS = new Series("kb.parse.dead_letter.depth");
    private static final Series OLDEST = new Series("kb.parse.queue.oldest_age_seconds");
    private final MonitoringSample snapshot = new MonitoringSample("parse",
            Map.of(BACKLOG, 0.0, DEAD_LETTERS, 0.0, OLDEST, 0.0));
    private final Clock clock;

    @Autowired
    public ParseTaskMetrics(MeterRegistry registry, MonitoringJdbc jdbc, MonitoringSnapshotExporter exporter) {
        this(registry, jdbc.template(), exporter, Clock.systemUTC());
    }

    public ParseTaskMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this(registry, jdbc, new MonitoringSnapshotExporter(), Clock.systemUTC());
    }

    public ParseTaskMetrics(MeterRegistry registry, JdbcTemplate jdbc, MonitoringSnapshotExporter exporter, Clock clock) {
        this.clock = clock;
        this.registry = registry;
        this.jdbc = jdbc;
        this.claimed = Counter.builder("kb.parse.tasks.claimed").register(registry);
        this.completed = Counter.builder("kb.parse.tasks.completed").register(registry);
        this.retried = Counter.builder("kb.parse.tasks.retried").register(registry);
        this.deadLettered = Counter.builder("kb.parse.tasks.dead_lettered").register(registry);
        this.leaseLost = Counter.builder("kb.parse.tasks.lease_lost").register(registry);
        this.alertFailed = Counter.builder("kb.parse.alerts.failed").register(registry);
        exporter.add(snapshot);
        exporter.bindTo(registry);
    }

    /**
     * 仅供无 Spring 的 Worker 单元测试，生产实例由 MeterRegistry 注入。
     */
    public ParseTaskMetrics() {
        this(new SimpleMeterRegistry(), null);
    }

    public void claimed(int count) {
        if (count > 0) {
            claimed.increment(count);
        }
    }

    public void completed() {
        completed.increment();
    }

    public void retried(String failureClass) {
        retried.increment();
        // Prometheus 同名指标必须使用相同标签键集合，分类与无标签总量分开命名。
        counterByClass("kb.parse.tasks.retried.by_class", failureClass).increment();
    }

    public void deadLettered(String failureClass) {
        deadLettered.increment();
        counterByClass("kb.parse.tasks.dead_lettered.by_class", failureClass).increment();
    }

    public void leaseLost() {
        leaseLost.increment();
    }

    public void alertFailed() {
        alertFailed.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void observe(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(registry.timer("kb.parse.task.duration"));
        }
    }

    @Scheduled(fixedDelayString = "#{@monitoringSchedule.delayMillis}",
            initialDelayString = "#{@monitoringSchedule.delayMillis}")
    public synchronized void refreshGauges() {
        if (jdbc == null) {
            return;
        }
        try {
            Long backlog = jdbc.queryForObject("""
                    SELECT count(*) FROM kb_parse_task
                     WHERE status = 'pending' AND attempts < max_attempts
                       AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                    """, Long.class);
            Long deadLetters = jdbc.queryForObject(
                    "SELECT count(*) FROM kb_parse_task_dead_letter WHERE status = 'OPEN'", Long.class);
            Long oldest = jdbc.queryForObject("""
                    SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(COALESCE(next_attempt_at, created_at))))::bigint, 0)
                      FROM kb_parse_task
                     WHERE status = 'pending' AND attempts < max_attempts
                    """, Long.class);
            snapshot.success(Map.of(BACKLOG, backlog.doubleValue(), DEAD_LETTERS, deadLetters.doubleValue(),
                    OLDEST, (double) Math.max(0, oldest)), clock.instant().getEpochSecond());
        } catch (RuntimeException unavailable) {
            snapshot.failure();
        }
    }

    private Counter counterByClass(String name, String failureClass) {
        String value = failureClass == null ? "UNKNOWN" : failureClass.toUpperCase(Locale.ROOT);
        return Counter.builder(name).tag("failure_class", value).register(registry);
    }
}
