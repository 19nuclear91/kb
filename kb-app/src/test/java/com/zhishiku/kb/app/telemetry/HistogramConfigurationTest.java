package com.zhishiku.kb.app.telemetry;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistogramConfigurationTest {
    @Test
    void timerSnapshotsIncludeBudgetBoundariesAndConfiguredTaskTimeout() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new HistogramConfiguration().telemetryHistograms(
                    new MockEnvironment().withProperty("kb.worker.task-timeout-seconds", "120")));
            Map.of("kb.search.stage.duration", new double[]{1.5, 2, 3, 8},
                    "kb.search.options.stage.duration", new double[]{1.5, 2, 3, 8},
                    "kb.model.call.duration", new double[]{1, 5, 15, 30, 60},
                    "kb.parse.task.duration", new double[]{1, 5, 30, 60, 120, 300},
                    "http.server.requests", new double[]{0.1, 0.5, 2, 10}).forEach((name, expected) -> {
                var timer = Timer.builder(name).register(registry);
                assertThat(Arrays.stream(timer.takeSnapshot().histogramCounts()).mapToDouble(count -> count.bucket() / 1_000_000_000.0).toArray())
                        .containsExactly(expected);
            });
        } finally {
            registry.close();
        }
    }

    @Test
    void acceptsCustomBoundariesAndRejectsInvalidValuesBeforeAutoConfiguration() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new HistogramConfiguration().telemetryHistograms(new MockEnvironment()
                    .withProperty("kb.telemetry.histograms.model", "2s,9s")));
            assertThat(registry.timer("kb.model.call.duration").takeSnapshot().histogramCounts()).hasSize(2);
        } finally {
            registry.close();
        }
        for (String value : new String[]{"0,1", "2s,1s", "1s,1s", "NaN", "secret"}) {
            var environment = new MockEnvironment().withProperty("kb.telemetry.histograms.model", value);
            assertThatThrownBy(() -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(environment, null))
                    .hasMessage("kb.telemetry.histograms.model").hasNoCause();
        }
    }
}
