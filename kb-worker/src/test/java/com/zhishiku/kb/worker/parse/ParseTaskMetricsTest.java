package com.zhishiku.kb.worker.parse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ParseTaskMetricsTest {

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
        assertThat(registry.get("kb.parse.tasks.retried").tag("failure_class", "RETRYABLE").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("kb.parse.dead_letter.depth").gauge()).isNotNull();
        assertThat(registry.get("kb.parse.task.duration").timer()).isNotNull();
        assertThat(registry.getMeters().stream().allMatch(m -> m.getId().getTags().stream()
                .noneMatch(tag -> tag.getKey().matches("task|version|object|path|id"))))
                .isTrue();
    }
}
