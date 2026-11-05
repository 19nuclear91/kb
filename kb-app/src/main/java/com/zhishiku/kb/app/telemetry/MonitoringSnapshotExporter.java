package com.zhishiku.kb.app.telemetry;

import com.zhishiku.kb.common.monitoring.MonitoringSample;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Prometheus 必须一次捕获来源快照，不能通过多个独立 Gauge 回调读取 volatile。 */
@Component
public class MonitoringSnapshotExporter implements MeterBinder {
    private final CopyOnWriteArrayList<MonitoringSample> samples = new CopyOnWriteArrayList<>();
    private final Set<MeterRegistry> bound = Collections.newSetFromMap(new IdentityHashMap<>());

    public synchronized void add(MonitoringSample sample) {
        samples.add(sample);
        for (MeterRegistry registry : bound) {
            if (!(registry instanceof PrometheusMeterRegistry)) {
                bindGauges(registry, sample);
            }
        }
    }

    @Override
    public synchronized void bindTo(MeterRegistry registry) {
        if (registry instanceof CompositeMeterRegistry composite) {
            for (MeterRegistry child : composite.getRegistries()) {
                bindTo(child);
            }
            return;
        }
        if (!bound.add(registry)) {
            return;
        }
        if (registry instanceof PrometheusMeterRegistry prometheus) {
            prometheus.getPrometheusRegistry().register((MultiCollector) this::collect);
        } else {
            for (MonitoringSample sample : samples) {
                bindGauges(registry, sample);
            }
        }
    }

    private void bindGauges(MeterRegistry registry, MonitoringSample sample) {
        for (MonitoringSample.Series series : sample.export(sample.snapshot()).keySet()) {
            Gauge.builder(series.name(), sample, value -> value.export(value.snapshot()).get(series))
                    .tags(series.labels().entrySet().stream().map(e -> Tag.of(e.getKey(), e.getValue())).toList())
                    .register(registry);
        }
    }

    private MetricSnapshots collect() {
        Map<String, GaugeSnapshot.Builder> families = new LinkedHashMap<>();
        for (MonitoringSample sample : samples) {
            // 每个来源恰好读取一次；序列化期间的新发布不影响本次结果，多个抓取线程互不共享游标。
            MonitoringSample.Snapshot captured = sample.snapshot();
            for (var entry : sample.export(captured).entrySet()) {
                MonitoringSample.Series series = entry.getKey();
                String name = series.name().replace('.', '_');
                var builder = families.computeIfAbsent(name, key -> GaugeSnapshot.builder().name(key));
                builder.dataPoint(new GaugeSnapshot.GaugeDataPointSnapshot(entry.getValue(),
                        Labels.of(series.labels().keySet().toArray(String[]::new),
                                series.labels().values().toArray(String[]::new)), null));
            }
        }
        return new MetricSnapshots(families.values().stream().map(GaugeSnapshot.Builder::build).toArray(GaugeSnapshot[]::new));
    }
}
