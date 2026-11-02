package com.zhishiku.kb.worker.parse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ParseTaskMetricsTest {

    @Test
    void histogramIncludesTaskDeadlineAndPreservesExistingMetricContract() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new com.zhishiku.kb.worker.telemetry.HistogramConfiguration()
                    .telemetryHistograms(new org.springframework.mock.env.MockEnvironment()
                            .withProperty("kb.worker.task-timeout-seconds", "600")));
            var metrics = new ParseTaskMetrics(registry, mock(JdbcTemplate.class));
            metrics.claimed(1);
            metrics.completed();
            metrics.retried("RETRYABLE");
            metrics.deadLettered("PERMANENT");
            metrics.observe(metrics.startTimer());
            // SimpleMeterRegistry 另外把 SLO 桶注册为辅助 gauge，业务名称集合单独核对。
            var businessMeters = registry.getMeters().stream()
                    .filter(meter -> !meter.getId().getName().equals("kb.parse.task.duration.histogram")).toList();
            assertThat(registry.find("kb.parse.task.duration.histogram").gauges()).hasSize(6);
            assertThat(businessMeters).extracting(meter -> meter.getId().getName()).containsOnly(
                    "kb.parse.tasks.claimed", "kb.parse.tasks.completed", "kb.parse.tasks.retried", "kb.parse.tasks.dead_lettered",
                    "kb.parse.tasks.lease_lost", "kb.parse.alerts.failed", "kb.parse.queue.depth", "kb.parse.dead_letter.depth",
                    "kb.parse.queue.oldest_age_seconds", "kb.parse.task.duration",
                    "kb.parse.tasks.retried.by_class", "kb.parse.tasks.dead_lettered.by_class");
            var timer = registry.get("kb.parse.task.duration").timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.getId().getTags()).isEmpty();
            assertThat(java.util.Arrays.stream(timer.takeSnapshot().histogramCounts())
                    .mapToDouble(bucket -> bucket.bucket() / 1_000_000_000.0).toArray()).containsExactly(1, 5, 30, 60, 300, 600);
            assertThat(businessMeters).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isEqualTo("failure_class");
                assertThat(tag.getValue()).isIn("RETRYABLE", "PERMANENT");
            }));
        } finally {
            registry.close();
        }
    }

    @Test
    void registersLowCardinalityNamesAndFailureClassOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ParseTaskMetrics metrics = new ParseTaskMetrics(registry, mock(JdbcTemplate.class));

        metrics.claimed(2);
        metrics.retried("RETRYABLE");
        metrics.deadLettered("PERMANENT");
        metrics.leaseLost();
        metrics.alertFailed();
        metrics.observe(metrics.startTimer());

        assertThat(registry.get("kb.parse.tasks.claimed").counter().count()).isEqualTo(2);
        assertThat(registry.get("kb.parse.tasks.retried").counter().count()).isEqualTo(1);
        assertThat(registry.get("kb.parse.tasks.retried.by_class").tag("failure_class", "RETRYABLE").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("kb.parse.tasks.retried").counter().getId().getTags()).isEmpty();
        assertThat(registry.get("kb.parse.tasks.dead_lettered").counter().getId().getTags()).isEmpty();
        assertThat(registry.get("kb.parse.dead_letter.depth").gauge()).isNotNull();
        assertThat(registry.get("kb.parse.task.duration").timer()).isNotNull();
        assertThat(registry.getMeters().stream().allMatch(m -> m.getId().getTags().stream()
                .noneMatch(tag -> tag.getKey().matches("task|version|object|path|id"))))
                .isTrue();
    }
}
