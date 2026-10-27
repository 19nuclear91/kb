package com.zhishiku.kb.eval.comparison;

import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.metrics.Metric;
import com.zhishiku.kb.eval.metrics.MetricModel.CaseMetrics;
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
import static org.assertj.core.api.Assertions.within;

class ComparisonCalculatorTest {
    private final ComparisonCalculator calculator = new ComparisonCalculator();

    @Test
    void noBaselineOnlySummarizesAndInvalidSelectionIsRejected() {
        RunMetrics baseline = run("base", 0, 1, 0.2, null);
        assertThat(calculator.compare(List.of(baseline), null)).isEmpty();
        assertThatThrownBy(() -> calculator.compare(List.of(baseline), "missing")).isInstanceOf(EvaluationException.class);
        assertThatThrownBy(() -> calculator.compare(List.of(baseline), "base")).isInstanceOf(EvaluationException.class);
    }

    @Test
    void exposesOppositeClassChangesRegressionDirectionAndNullDifference() {
        RunMetrics baseline = run("base", 0, 1, 0.2, null);
        RunMetrics candidate = run("candidate", 1, 0, 0.4, 0.0);
        var comparison = calculator.compare(List.of(candidate, baseline), "base").getFirst();
        assertThat(comparison.overall().getFirst().rawDelta()).isZero();
        assertThat(comparison.groups().get(0).deltas().getFirst().rawDelta()).isEqualTo(1.0);
        assertThat(comparison.groups().get(1).deltas().getFirst().rawDelta()).isEqualTo(-1.0);
        assertThat(comparison.cases().getFirst().deltas().get(1).rawDelta()).isCloseTo(0.2, within(1e-12));
        var nullable = comparison.cases().get(1).deltas().get(1);
        assertThat(nullable.baseline()).isNull();
        assertThat(nullable.candidate()).isZero();
        assertThat(nullable.rawDelta()).isNull();
        assertThat(comparison.regressions()).anySatisfy(regression -> {
            assertThat(regression.metric()).isEqualTo(Metric.RECALL);
            assertThat(regression.caseIds()).containsExactly("c2");
        }).anySatisfy(regression -> {
            assertThat(regression.metric()).isEqualTo(Metric.DUPLICATE_RATE);
            assertThat(regression.caseIds()).containsExactly("c1");
        });
    }

    @Test
    void candidateOrderIsDeterministic() {
        var runs = List.of(run("z", 1, 0, 0.1, null), run("base", 0, 1, 0.2, null), run("a", 1, 1, 0.0, null));
        assertThat(calculator.compare(runs, "base")).extracting(value -> value.candidateId()).containsExactly("a", "z");
    }

    private static RunMetrics run(String id, double firstRecall, double secondRecall, Double firstDup,
                                  Double secondDup) {
        List<MetricValue> first = values(firstRecall, firstDup);
        List<MetricValue> second = values(secondRecall, secondDup);
        List<MetricValue> overall = List.of(new MetricValue(Metric.RECALL, 5, (firstRecall + secondRecall) / 2, 2),
                new MetricValue(Metric.DUPLICATE_RATE, 5, secondDup == null ? firstDup : (firstDup + secondDup) / 2,
                        secondDup == null ? 1 : 2));
        return new RunMetrics(new Run(id, "config", SourceType.SYNTHETIC, "a", "b", Map.of()),
                new GroupMetrics(null, 2, overall), List.of(new GroupMetrics("a", 1, first), new GroupMetrics("b", 1,
                second)),
                List.of(new CaseMetrics("c1", List.of("a"), first), new CaseMetrics("c2", List.of("b"), second)),
                new LatencyStats(2, 0, null, null));
    }

    private static List<MetricValue> values(double recall, Double dup) {
        return List.of(new MetricValue(Metric.RECALL, 5, recall, 1), new MetricValue(Metric.DUPLICATE_RATE, 5, dup,
                dup == null ? 0 : 1));
    }
}
