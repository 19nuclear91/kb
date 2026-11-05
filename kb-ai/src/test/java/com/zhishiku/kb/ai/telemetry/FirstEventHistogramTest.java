package com.zhishiku.kb.ai.telemetry;

import com.zhishiku.kb.ai.model.ModelEvent;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirstEventHistogramTest {
    private static final String CONFIG = "kb.telemetry.histograms.first-event";
    private static final String MODEL = "kb_model_first_text_delay_seconds";
    private static final String CHAT = "kb_chat_first_event_delay_seconds";
    private static final String[] FORMATS = {"text/plain;version=0.0.4", "application/openmetrics-text;version=1.0.0;charset=utf-8"};

    @ParameterizedTest
    @ValueSource(strings = {"text/plain;version=0.0.4", "application/openmetrics-text;version=1.0.0;charset=utf-8"})
    void actualTelemetryExportsDefaultBucketsForBothDashboardMetrics(String format) {
        var clock = new MockClock();
        var registry = registry(clock, new MockEnvironment());
        try {
            recordFirstEvents(registry, clock);
            String scrape = registry.scrape(format);
            assertHistogram(scrape, MODEL, Map.of(0.1, 0.0, 0.5, 1.0, 1.0, 1.0, 2.0, 1.0,
                    5.0, 1.0, 10.0, 1.0, 30.0, 1.0, 60.0, 1.0, Double.POSITIVE_INFINITY, 1.0), 0.25);
            assertHistogram(scrape, CHAT, Map.of(0.1, 1.0, 0.5, 1.0, 1.0, 1.0, 2.0, 1.0,
                    5.0, 1.0, 10.0, 1.0, 30.0, 1.0, 60.0, 1.0, Double.POSITIVE_INFINITY, 1.0), 0.1);
            assertThat(scrape).doesNotContain("kb_chat_stream_duration_seconds_bucket");
        } finally {
            registry.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customScalarAndListBoundariesApplyToBothTimersWithoutChangingModelCallBuckets(boolean indexed) {
        var environment = new MockEnvironment();
        if (indexed) {
            environment.withProperty(CONFIG + "[0]", "200ms").withProperty(CONFIG + "[1]", "400ms")
                    .withProperty(CONFIG + "[2]", "800ms");
        } else {
            environment.withProperty(CONFIG, "200ms,400ms,800ms");
        }
        var clock = new MockClock();
        var registry = registry(clock, environment);
        try {
            recordFirstEvents(registry, clock);
            for (String format : FORMATS) {
                String scrape = registry.scrape(format);
                assertHistogram(scrape, MODEL, Map.of(0.2, 0.0, 0.4, 1.0, 0.8, 1.0, Double.POSITIVE_INFINITY, 1.0), 0.25);
                assertHistogram(scrape, CHAT, Map.of(0.2, 1.0, 0.4, 1.0, 0.8, 1.0, Double.POSITIVE_INFINITY, 1.0), 0.1);
            }
            assertThat(registry.get("kb.model.call.duration").timer().takeSnapshot().histogramCounts())
                    .extracting(count -> count.bucket() / 1_000_000_000.0).containsExactly(1.0, 5.0, 15.0, 30.0, 60.0);
        } finally {
            registry.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0,1", "2s,1s", "1s,1s", "NaN", "invalid"})
    void invalidFirstEventBoundariesFailBeforeAutoConfiguration(String value) {
        var environment = new MockEnvironment().withProperty(CONFIG, value);
        assertThatThrownBy(() -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(environment, null))
                .hasMessage(CONFIG).hasNoCause();
    }

    @Test
    void streamsWithoutContentDoNotInventFirstEventSamples() {
        var registry = registry(new MockClock(), new MockEnvironment());
        try {
            new ModelTelemetry(registry).observe(() -> Flux.just(new ModelEvent.FinalUsage(1, 0))).blockLast();
            new StreamTelemetry(registry).observe(() -> Flux.just(
                    ServerSentEvent.<Object>builder().comment("heartbeat").build(),
                    ServerSentEvent.<Object>builder("complete").event("done").build())).blockLast();
            assertThat(registry.scrape()).doesNotContain(MODEL, CHAT);
        } finally {
            registry.close();
        }
    }

    private static PrometheusMeterRegistry registry(MockClock clock, MockEnvironment environment) {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new PrometheusRegistry(), clock);
        registry.config().meterFilter(new HistogramConfiguration().telemetryHistograms(environment));
        return registry;
    }

    private static void recordFirstEvents(PrometheusMeterRegistry registry, MockClock clock) {
        new ModelTelemetry(registry).observe(() -> Flux.concat(Flux.just(new ModelEvent.Delta("")), Flux.defer(() -> {
            clock.add(Duration.ofMillis(250));
            return Flux.just(new ModelEvent.Delta("first"), new ModelEvent.Delta("more"));
        }))).blockLast();
        // citation 是 SSE 的首个内容事件；后续 delta 不得重新计时或重复采样。
        new StreamTelemetry(registry).observe(() -> Flux.concat(
                Flux.just(ServerSentEvent.<Object>builder().comment("heartbeat").build()),
                Flux.defer(() -> {
                    clock.add(Duration.ofMillis(100));
                    return Flux.just(ServerSentEvent.<Object>builder("reference").event("citation").build());
                }), Flux.defer(() -> {
                    clock.add(Duration.ofMillis(900));
                    return Flux.just(ServerSentEvent.<Object>builder("text").event("delta").build());
                }))).blockLast();
    }

    private static void assertHistogram(String scrape, String name, Map<Double, Double> expected, double sum) {
        var buckets = new LinkedHashMap<Double, Double>();
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(name) + "_bucket\\{le=\"([^\"]+)\"} (\\S+)").matcher(scrape);
        while (matcher.find()) {
            double boundary = "+Inf".equals(matcher.group(1)) ? Double.POSITIVE_INFINITY : Double.parseDouble(matcher.group(1));
            buckets.put(boundary, Double.parseDouble(matcher.group(2)));
        }
        assertThat(buckets).as(name).containsExactlyInAnyOrderEntriesOf(expected);
        assertSample(scrape, name + "_count", 1);
        assertSample(scrape, name + "_sum", sum);
    }

    private static void assertSample(String scrape, String name, double expected) {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(name) + " (\\S+)").matcher(scrape);
        assertThat(matcher.find()).as(name).isTrue();
        assertThat(Double.parseDouble(matcher.group(1))).as(name).isEqualTo(expected);
    }
}
