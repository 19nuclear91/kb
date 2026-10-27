package com.zhishiku.kb.eval.comparison;

import com.zhishiku.kb.eval.cli.ExitCodes;
import com.zhishiku.kb.eval.comparison.ThresholdReader.Rule;
import com.zhishiku.kb.eval.comparison.ThresholdReader.Thresholds;
import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.metrics.Metric;
import com.zhishiku.kb.eval.metrics.MetricModel.GroupMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.LatencyStats;
import com.zhishiku.kb.eval.metrics.MetricModel.MetricValue;
import com.zhishiku.kb.eval.metrics.MetricModel.RunMetrics;
import com.zhishiku.kb.eval.model.InputModel.Run;
import com.zhishiku.kb.eval.model.InputModel.SourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityGateTest {
    private final QualityGate gate = new QualityGate();

    @Test
    void noGateAndEqualityPassAndExitCodesAreDistinct() {
        var runs = List.of(run("r", 0.7, 0.4));
        assertThat(ExitCodes.forGate(gate.evaluate(runs, null, null))).isZero();
        var outcome = gate.evaluate(runs, null, limits(new Rule(Metric.RECALL, 5, 0.7, null, null),
                new Rule(Metric.DUPLICATE_RATE, 5, null, 0.4, null)));
        assertThat(outcome.rules()).hasSize(2).allSatisfy(rule -> assertThat(rule.passed()).isTrue());
        assertThat(ExitCodes.forGate(outcome)).isZero();
        assertThat(ExitCodes.forError()).isEqualTo(2);
    }

    @Test
    void toleranceIsAtMostOneTrillionthAndDisplayedRoundingDoesNotDetermineGate() {
        var rule = limits(new Rule(Metric.RECALL, 5, 0.7, null, null));
        assertThat(gate.evaluate(List.of(run("r", 0.7 - 5e-13, 0.0)), null, rule).passed()).isTrue();
        assertThat(gate.evaluate(List.of(run("r", 0.7 - 2e-12, 0.0)), null, rule).passed()).isFalse();
        var failed = gate.evaluate(List.of(run("r", 0.69999997, 0.0)), null, rule);
        assertThat(failed.passed()).isFalse();
        assertThat(ExitCodes.forGate(failed)).isEqualTo(1);
        assertThat(failed.rules().getFirst().actual()).isEqualTo(0.69999997);
        assertThat(failed.rules().getFirst().min()).isEqualTo(0.7);
    }

    @Test
    void appliesRegressionInCorrectDirectionAndChecksEveryCandidate() {
        var rules = limits(new Rule(Metric.RECALL, 5, null, null, 0.1),
                new Rule(Metric.DUPLICATE_RATE, 5, null, null, 0.1));
        var failed = gate.evaluate(List.of(run("base", 0.8, 0.2), run("z", 0.9, 0.1), run("a", 0.7, 0.31)), "base",
                rules);
        assertThat(failed.passed()).isFalse();
        assertThat(failed.rules()).hasSize(4).extracting(value -> value.runId()).containsExactly("a", "a", "z", "z");
        assertThat(failed.rules().get(0).passed()).isTrue();
        assertThat(failed.rules().get(1).passed()).isFalse();
        assertThat(failed.rules().get(1).baseline()).isEqualTo(0.2);
        assertThat(failed.rules().get(1).maxRegression()).isEqualTo(0.1);
        assertThat(failed.rules().get(2).observedRegression()).isNegative();
    }

    @Test
    void absoluteRulesApplyToAllWithoutBaselineAndOnlyCandidatesWithBaseline() {
        var rules = limits(new Rule(Metric.RECALL, 5, 0.7, null, null));
        var runs = List.of(run("base", 0.0, null), run("candidate", 0.8, 0.0));
        assertThat(gate.evaluate(runs, null, rules).passed()).isFalse();
        var outcome = gate.evaluate(runs, "base", rules);
        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.rules()).hasSize(1);
    }

    @Test
    void nullCandidateOrRequiredBaselineIsConfigurationError() {
        assertThatThrownBy(() -> gate.evaluate(List.of(run("r", null, null)), null,
                limits(new Rule(Metric.RECALL, 5, 0.5, null, null)))).isInstanceOf(EvaluationException.class);
        assertThatThrownBy(() -> gate.evaluate(List.of(run("base", null, null), run("r", 0.5, null)), "base",
                limits(new Rule(Metric.RECALL, 5, null, null, 0.1)))).isInstanceOf(EvaluationException.class);
        assertThatThrownBy(() -> gate.evaluate(List.of(run("r", 0.5, null)), null,
                limits(new Rule(Metric.RECALL, 5, null, null, 0.1)))).isInstanceOf(EvaluationException.class);
    }

    private static Thresholds limits(Rule... rules) {
        return new Thresholds(List.of(rules), "sha");
    }

    private static RunMetrics run(String id, Double recall, Double duplicate) {
        var metrics = List.of(new MetricValue(Metric.RECALL, 5, recall, recall == null ? 0 : 1),
                new MetricValue(Metric.DUPLICATE_RATE, 5, duplicate, duplicate == null ? 0 : 1));
        return new RunMetrics(new Run(id, "config", SourceType.SYNTHETIC, "a", "b", Map.of()),
                new GroupMetrics(null, 1, metrics), List.of(), List.of(), new LatencyStats(1, 0, null, null));
    }
}
