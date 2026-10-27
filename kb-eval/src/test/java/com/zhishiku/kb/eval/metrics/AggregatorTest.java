package com.zhishiku.kb.eval.metrics;

import com.zhishiku.kb.eval.metrics.MetricModel.RunMetrics;
import com.zhishiku.kb.eval.model.InputModel.Dataset;
import com.zhishiku.kb.eval.model.InputModel.Hit;
import com.zhishiku.kb.eval.model.InputModel.Identity;
import com.zhishiku.kb.eval.model.InputModel.LabelCase;
import com.zhishiku.kb.eval.model.InputModel.ResultCase;
import com.zhishiku.kb.eval.model.InputModel.Run;
import com.zhishiku.kb.eval.model.InputModel.SourceType;
import com.zhishiku.kb.eval.model.InputModel.VersionKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AggregatorTest {
    private final Aggregator aggregator = new Aggregator();

    @Test
    void usesEligibleDenominatorsAndCountsMultiTagsOnlyOnceOverall() {
        var c1 = new LabelCase("c1", List.of("term", "synonym"), false, Map.of(new VersionKey(1, 2), 3));
        var c2 = new LabelCase("c2", List.of(), false, c1.relevance());
        var c3 = new LabelCase("c3", List.of(), true, Map.of());
        Dataset dataset = new Dataset(new Identity("d", "v1", "s1"), "a", "b", Map.of("c1", c1, "c2", c2, "c3", c3));
        Run run = run(Map.of("c1", new ResultCase("c1", List.of(new Hit(1, 2, 3), new Hit(1, 2, 4)), 2, null),
                "c2", new ResultCase("c2", List.of(), 0, null), "c3", new ResultCase("c3", List.of(), 0, null)));
        RunMetrics metrics = aggregator.calculate(dataset, run, List.of(5));
        assertThat(metrics.overall().totalCases()).isEqualTo(3);
        assertThat(MetricModel.find(metrics.overall().metrics(), Metric.HIT, 5).eligibleCases()).isEqualTo(2);
        assertThat(MetricModel.find(metrics.overall().metrics(), Metric.HIT, 5).value()).isEqualTo(0.5);
        var duplicate = MetricModel.find(metrics.overall().metrics(), Metric.DUPLICATE_RATE, 5);
        assertThat(duplicate.eligibleCases()).isEqualTo(1);
        assertThat(duplicate.value()).isEqualTo(0.5);
        assertThat(metrics.groups()).extracting(group -> group.tag()).containsExactly("UNTAGGED", "synonym", "term");
        assertThat(metrics.groups()).extracting(group -> group.totalCases()).containsExactly(2, 1, 1);
        var empty = MetricModel.find(metrics.overall().metrics(), Metric.EMPTY_RESULT_ACCURACY, null);
        assertThat(empty.value()).isEqualTo(1.0);
        assertThat(empty.eligibleCases()).isEqualTo(1);
    }

    @Test
    void allEmptyExpectationsLeaveRelevanceNullWithZeroEligibleCases() {
        LabelCase label = new LabelCase("c1", List.of(), true, Map.of());
        Dataset dataset = new Dataset(new Identity("d", "v1", "s1"), "a", "b", Map.of("c1", label));
        RunMetrics metrics = aggregator.calculate(dataset,
                run(Map.of("c1", new ResultCase("c1", List.of(), 0, null))), List.of(5));
        var hit = MetricModel.find(metrics.overall().metrics(), Metric.HIT, 5);
        assertThat(hit.value()).isNull();
        assertThat(hit.eligibleCases()).isZero();
    }

    @Test
    void latencyOnlyUsesRecordedSamplesAndNearestRank() {
        Map<String, ResultCase> cases = new LinkedHashMap<>();
        cases.put("c1", new ResultCase("c1", List.of(), 0, 30.0));
        cases.put("c2", new ResultCase("c2", List.of(), 0, null));
        cases.put("c3", new ResultCase("c3", List.of(), 0, 10.0));
        cases.put("c4", new ResultCase("c4", List.of(), 0, null));
        cases.put("c5", new ResultCase("c5", List.of(), 0, 20.0));
        var stats = aggregator.latency(run(cases));
        assertThat(stats.totalCases()).isEqualTo(5);
        assertThat(stats.recordedCases()).isEqualTo(3);
        assertThat(stats.meanMs()).isCloseTo(20.0, within(1e-12));
        assertThat(stats.p95Ms()).isEqualTo(30.0);
        var unknown = aggregator.latency(run(Map.of("c1", new ResultCase("c1", List.of(), 0, null))));
        assertThat(unknown.recordedCases()).isZero();
        assertThat(unknown.meanMs()).isNull();
        assertThat(unknown.p95Ms()).isNull();
    }

    @Test
    void finiteLargeLatenciesDoNotOverflowMean() {
        var stats = aggregator.latency(run(Map.of("c1", new ResultCase("c1", List.of(), 0, 1e308),
                "c2", new ResultCase("c2", List.of(), 0, 1e308))));
        assertThat(stats.meanMs()).isEqualTo(1e308);
    }

    @Test
    void maximumLatenciesRetainFiniteMean() {
        var stats = aggregator.latency(run(Map.of("c1", new ResultCase("c1", List.of(), 0, Double.MAX_VALUE),
                "c2", new ResultCase("c2", List.of(), 0, Double.MAX_VALUE),
                "c3", new ResultCase("c3", List.of(), 0, Double.MAX_VALUE))));
        assertThat(stats.meanMs()).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void smallestPositiveLatenciesRetainNonzeroMean() {
        var stats = aggregator.latency(run(Map.of("c1", new ResultCase("c1", List.of(), 0, Double.MIN_VALUE),
                "c2", new ResultCase("c2", List.of(), 0, Double.MIN_VALUE))));
        assertThat(stats.meanMs()).isEqualTo(Double.MIN_VALUE);
    }

    @Test
    void meanHandlesZeroAndVeryLargeLatencies() {
        var stats = aggregator.latency(run(Map.of("c1", new ResultCase("c1", List.of(), 0, 0.0),
                "c2", new ResultCase("c2", List.of(), 0, Double.MAX_VALUE / 2),
                "c3", new ResultCase("c3", List.of(), 0, Double.MAX_VALUE))));
        assertThat(stats.meanMs()).isEqualTo(Double.MAX_VALUE / 2);
    }

    @Test
    void meanHandlesZeroAndSubnormalLatencies() {
        var stats = aggregator.latency(run(Map.of("c1", new ResultCase("c1", List.of(), 0, 0.0),
                "c2", new ResultCase("c2", List.of(), 0, Double.MIN_VALUE),
                "c3", new ResultCase("c3", List.of(), 0, Double.MIN_VALUE))));
        assertThat(stats.meanMs()).isEqualTo(Double.MIN_VALUE);
    }

    @Test
    void zeroLatenciesHaveZeroMean() {
        var stats = aggregator.latency(run(Map.of("c1", new ResultCase("c1", List.of(), 0, 0.0),
                "c2", new ResultCase("c2", List.of(), 0, 0.0))));
        assertThat(stats.meanMs()).isZero();
    }

    private static Run run(Map<String, ResultCase> cases) {
        return new Run("r1", "config", SourceType.SYNTHETIC, "a", "b", cases);
    }
}
