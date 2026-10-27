package com.zhishiku.kb.worker.parse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong backlogGauge = new AtomicLong();
    private final AtomicLong deadLetterGauge = new AtomicLong();
    private final AtomicLong oldestAgeGauge = new AtomicLong();

    public ParseTaskMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.claimed = Counter.builder("kb.parse.tasks.claimed").register(registry);
        this.completed = Counter.builder("kb.parse.tasks.completed").register(registry);
        this.retried = Counter.builder("kb.parse.tasks.retried").register(registry);
        this.deadLettered = Counter.builder("kb.parse.tasks.dead_lettered").register(registry);
        this.leaseLost = Counter.builder("kb.parse.tasks.lease_lost").register(registry);
        this.alertFailed = Counter.builder("kb.parse.alerts.failed").register(registry);
        Gauge.builder("kb.parse.queue.depth", backlogGauge, AtomicLong::get).register(registry);
        Gauge.builder("kb.parse.dead_letter.depth", deadLetterGauge, AtomicLong::get).register(registry);
        Gauge.builder("kb.parse.queue.oldest_age_seconds", oldestAgeGauge, AtomicLong::get).register(registry);
    }

    /**
     * 仅供无 Spring 的 Worker 单元测试，生产实例由 MeterRegistry 注入。
     */
    public ParseTaskMetrics() {
        this(new SimpleMeterRegistry(), null);
    }

    public void claimed(int count) {
        if (count > 0) claimed.increment(count);
    }

    public void completed() {
        completed.increment();
    }

    public void retried(String failureClass) {
        retried.increment();
        counterByClass("kb.parse.tasks.retried", failureClass).increment();
    }

    public void deadLettered(String failureClass) {
        deadLettered.increment();
        counterByClass("kb.parse.tasks.dead_lettered", failureClass).increment();
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
        if (sample != null) sample.stop(registry.timer("kb.parse.task.duration"));
    }

    @Scheduled(fixedDelayString = "${kb.worker.metrics-gauge-interval-ms:30000}")
    public void refreshGauges() {
        if (jdbc == null) return;
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
        backlogGauge.set(backlog == null ? 0 : backlog);
        deadLetterGauge.set(deadLetters == null ? 0 : deadLetters);
        oldestAgeGauge.set(oldest == null ? 0 : Math.max(0, oldest));
    }

    private Counter counterByClass(String name, String failureClass) {
        String value = failureClass == null ? "UNKNOWN" : failureClass.toUpperCase(Locale.ROOT);
        return Counter.builder(name).tag("failure_class", value).register(registry);
    }
}
