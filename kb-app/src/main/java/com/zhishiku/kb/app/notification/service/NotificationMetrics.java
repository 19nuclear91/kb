package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.model.NotificationBacklog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 固定低基数指标：标签只有状态或阶段，不能带用户、文档或事件标识。 */
@Component
public class NotificationMetrics {
    /** 失败阶段固定枚举。 */
    public enum Stage { DISCOVER, CLAIM, LEASE, BATCH, POSTPONE, BACKLOG }

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong waitingSource = new AtomicLong();
    private final AtomicLong oldestAge = new AtomicLong();
    private final Counter takeovers;
    private final Counter readMismatches;
    private final Timer discovery;
    private final Map<Stage, Counter> failures = new EnumMap<>(Stage.class);

    public NotificationMetrics(MeterRegistry registry) {
        Gauge.builder("kb.notification.derivation.backlog", pending, AtomicLong::get).tag("state", "pending").register(registry);
        Gauge.builder("kb.notification.derivation.backlog", waitingSource, AtomicLong::get).tag("state", "waiting_source")
                .register(registry);
        Gauge.builder("kb.notification.derivation.oldest_age_seconds", oldestAge, AtomicLong::get).register(registry);
        takeovers = registry.counter("kb.notification.derivation.takeovers");
        readMismatches = registry.counter("kb.notification.read.mismatch");
        discovery = registry.timer("kb.notification.derivation.discover");
        for (var stage : Stage.values())
            failures.put(stage, registry.counter("kb.notification.derivation.failures", "stage", stage.name().toLowerCase()));
    }

    public void backlog(NotificationBacklog backlog) {
        pending.set(Math.max(0, backlog.pending()));
        waitingSource.set(Math.max(0, backlog.waitingSource()));
        oldestAge.set(Math.max(0, backlog.oldestAgeSeconds()));
    }

    public void takeover() {
        takeovers.increment();
    }

    public void failure(Stage stage) {
        failures.get(stage).increment();
    }

    /** 读取时 SQL 结果与 Java 复核不一致；只计数，不带任何标识。 */
    public void readMismatch() {
        readMismatches.increment();
    }

    /** 发现查询耗时，成功和失败都计入；反连接成本随事件总数增长，用于判断何时需要优化。 */
    public void timeDiscovery(Runnable discover) {
        discovery.record(discover);
    }
}
