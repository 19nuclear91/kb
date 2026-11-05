package com.zhishiku.kb.common.monitoring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringSampleTest {
    @Test
    void publishedValuesAndLabelsAreImmutableAndFailureKeepsLastSuccess() {
        var labels = new HashMap<>(Map.of("operation", "START"));
        var series = new MonitoringSample.Series("kb.embedding.events.persisted", labels);
        var values = new HashMap<>(Map.of(series, 0.0));
        var sample = new MonitoringSample("embedding", values);
        assertThat(sample.snapshot().available()).isFalse();
        assertThat(sample.snapshot().lastSuccess()).isZero();
        values.put(series, 4.0);
        labels.put("operation", "private");
        assertThat(sample.snapshot().values().get(series)).isZero();
        sample.success(values, 100);
        var first = sample.snapshot();
        values.put(series, 9.0);
        sample.failure();
        assertThat(first.values().get(series)).isEqualTo(4);
        assertThat(first.available()).isTrue();
        assertThat(sample.snapshot().available()).isFalse();
        assertThat(sample.snapshot().lastSuccess()).isEqualTo(100);
        assertThat(sample.snapshot().values()).isEqualTo(first.values());
        assertThat(series.labels()).containsEntry("operation", "START");
        assertThatThrownBy(() -> first.values().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void incompleteInvalidOrNegativeSnapshotsCannotBecomeSuccess() {
        var series = new MonitoringSample.Series("kb.parse.queue.depth");
        var sample = new MonitoringSample("parse", Map.of(series, 0.0));
        assertThatThrownBy(() -> sample.success(Map.of(), 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample.success(Map.of(series, Double.NaN), 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample.success(Map.of(series, -1.0), 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample.success(Map.of(series, 1.0), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sample.snapshot().lastSuccess()).isZero();
        assertThat(sample.snapshot().available()).isFalse();
    }
}
