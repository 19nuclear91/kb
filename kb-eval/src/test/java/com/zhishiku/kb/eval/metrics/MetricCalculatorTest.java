package com.zhishiku.kb.eval.metrics;

import com.zhishiku.kb.eval.metrics.MetricModel.CaseMetrics;
import com.zhishiku.kb.eval.model.InputModel.Hit;
import com.zhishiku.kb.eval.model.InputModel.LabelCase;
import com.zhishiku.kb.eval.model.InputModel.ResultCase;
import com.zhishiku.kb.eval.model.InputModel.VersionKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricCalculatorTest {
    private final MetricCalculator calculator = new MetricCalculator();
    private final LabelCase label = new LabelCase("c1", List.of(), false,
            Map.of(new VersionKey(1, 10), 3, new VersionKey(2, 20), 1, new VersionKey(3, 30), 2));
    private final List<Hit> ranked = List.of(new Hit(1, 10, 1), new Hit(1, 10, 2), new Hit(2, 20, 3), new Hit(3, 30,
            4));

    @Test
    void repeatsConsumeBudgetAndGainOnlyOnce() {
        CaseMetrics metrics = compute(label, ranked, List.of(3));
        assertValue(metrics, Metric.HIT, 3, 1);
        assertValue(metrics, Metric.RECALL, 3, 2.0 / 3);
        assertValue(metrics, Metric.MRR, 3, 1);
        assertValue(metrics, Metric.DUPLICATE_RATE, 3, 1.0 / 3);
        double independentIdcg = 7 + 3 / (Math.log(3) / Math.log(2)) + 0.5;
        assertValue(metrics, Metric.NDCG, 3, 7.5 / independentIdcg);
    }

    @Test
    void keepsOriginalRanksAndDoesNotPullPastK() {
        List<Hit> hits = List.of(new Hit(9, 90, 1), new Hit(9, 90, 2), new Hit(2, 20, 3));
        CaseMetrics metrics = compute(label, hits, List.of(2, 3));
        assertValue(metrics, Metric.HIT, 2, 0);
        assertValue(metrics, Metric.RECALL, 2, 0);
        assertValue(metrics, Metric.MRR, 3, 1.0 / 3);
        assertValue(metrics, Metric.RECALL, 3, 1.0 / 3);
    }

    @Test
    void sameDocumentDifferentVersionsRemainDistinctAndUnlabelledVersionGetsZero() {
        LabelCase versions = new LabelCase("c1", List.of(), false,
                Map.of(new VersionKey(1, 10), 3, new VersionKey(1, 11), 1));
        CaseMetrics metrics = compute(versions, List.of(new Hit(1, 12, 1), new Hit(1, 10, 2), new Hit(1, 11, 3)),
                List.of(3));
        assertValue(metrics, Metric.RECALL, 3, 1);
        assertValue(metrics, Metric.MRR, 3, 0.5);
        assertValue(metrics, Metric.DUPLICATE_RATE, 3, 0);
    }

    @Test
    void idealRankingGetsOneAndShortResultsKeepFullRecallDenominator() {
        assertValue(compute(label, List.of(ranked.get(0), ranked.get(3), ranked.get(2)), List.of(3)), Metric.NDCG, 3,
                1);
        CaseMetrics shortResult = compute(label, List.of(ranked.getFirst()), List.of(10));
        assertValue(shortResult, Metric.RECALL, 10, 1.0 / 3);
        assertThat(MetricModel.find(shortResult.metrics(), Metric.NDCG, 10).value()).isLessThan(1.0);
        assertValue(compute(label, ranked, List.of(1)), Metric.NDCG, 1, 1);
    }

    @Test
    void irrelevantAndEmptyResultsGetZeroRelevanceButEmptyDuplicateRateIsNull() {
        for (List<Hit> hits : List.of(List.<Hit>of(), List.of(new Hit(9, 90, 1)))) {
            CaseMetrics metrics = compute(label, hits, List.of(5));
            for (Metric metric : List.of(Metric.HIT, Metric.RECALL, Metric.MRR, Metric.NDCG)) {
                assertValue(metrics, metric, 5, 0);
            }
        }
        var duplicate = MetricModel.find(compute(label, List.of(), List.of(5)).metrics(), Metric.DUPLICATE_RATE, 5);
        assertThat(duplicate.value()).isNull();
        assertThat(duplicate.eligibleCases()).isZero();
    }

    @Test
    void expectedEmptyUsesFullResultAndRelevanceIsInapplicable() {
        LabelCase empty = new LabelCase("c1", List.of(), true, Map.of(new VersionKey(1, 10), 0));
        for (List<Hit> hits : List.of(List.<Hit>of(), ranked)) {
            CaseMetrics metrics = compute(empty, hits, List.of(1, 5));
            for (Metric metric : List.of(Metric.HIT, Metric.RECALL, Metric.MRR, Metric.NDCG)) {
                assertThat(MetricModel.find(metrics.metrics(), metric, 1).value()).isNull();
            }
            assertValue(metrics, Metric.EMPTY_RESULT_ACCURACY, null, hits.isEmpty() ? 1 : 0);
            assertThat(metrics.metrics().stream().filter(value -> value.metric() == Metric.EMPTY_RESULT_ACCURACY)).hasSize(1);
        }
        var original = new ResultCase("c1", List.of(), 3, null);
        assertValue(calculator.calculate(empty, original, List.of(1)), Metric.EMPTY_RESULT_ACCURACY, null, 0);
    }

    @Test
    void sortingBudgetsDoesNotReorderHits() {
        CaseMetrics metrics = compute(label, ranked, List.of(5, 1, 3));
        assertThat(metrics.metrics().stream().filter(value -> value.metric() == Metric.RECALL).map(value -> value.k()))
                .containsExactly(1, 3, 5);
    }

    private CaseMetrics compute(LabelCase sample, List<Hit> hits, List<Integer> ks) {
        return calculator.calculate(sample, new ResultCase(sample.caseId(), hits, hits.size(), null), ks);
    }

    static void assertValue(CaseMetrics metrics, Metric metric, Integer k, double expected) {
        assertThat(MetricModel.find(metrics.metrics(), metric, k).value()).isCloseTo(expected, within(1e-12));
    }
}
