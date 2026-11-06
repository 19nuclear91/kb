package com.zhishiku.kb.common.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 来源的业务值与质量信号只通过一个不可变引用发布；不依赖任何框架。 */
public final class MonitoringSample {
    public record Series(String name, Map<String, String> labels) {
        public Series {
            labels = Map.copyOf(labels);
        }

        public Series(String name) {
            this(name, Map.of());
        }
    }

    public record Snapshot(Map<Series, Double> values, boolean available, long lastSuccess) {
        public Snapshot {
            values = Map.copyOf(values);
        }
    }

    private final String source;
    private volatile Snapshot snapshot;

    public MonitoringSample(String source, Map<Series, Double> initial) {
        if (!java.util.Set.of("parse", "embedding", "settlement", "lifecycle").contains(source)) {
            throw new IllegalArgumentException("Unknown monitoring source");
        }
        this.source = source;
        this.snapshot = new Snapshot(initial, false, 0);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public synchronized void success(Map<Series, Double> values, long timestamp) {
        if (!values.keySet().equals(snapshot.values().keySet()) || timestamp <= 0
                || values.values().stream().anyMatch(value -> value == null || !Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException("Invalid monitoring snapshot");
        }
        snapshot = new Snapshot(values, true, timestamp);
    }

    public synchronized void failure() {
        Snapshot previous = snapshot;
        snapshot = new Snapshot(previous.values(), false, previous.lastSuccess());
    }

    public Map<Series, Double> export(Snapshot captured) {
        Objects.requireNonNull(captured);
        Map<Series, Double> values = new LinkedHashMap<>(captured.values());
        values.put(new Series("kb.monitoring.sample.available", Map.of("source", source)), captured.available() ? 1.0 : 0.0);
        values.put(new Series("kb.monitoring.sample.last_success_timestamp_seconds", Map.of("source", source)),
                (double) captured.lastSuccess());
        if (source.equals("embedding")) {
            values.put(new Series("kb.embedding.metrics.available"), captured.available() ? 1.0 : 0.0);
        }
        return Map.copyOf(values);
    }
}
