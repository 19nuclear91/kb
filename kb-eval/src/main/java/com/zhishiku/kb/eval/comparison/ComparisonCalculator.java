package com.zhishiku.kb.eval.comparison;

import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.EvaluationException.Code;
import com.zhishiku.kb.eval.metrics.Metric;
import com.zhishiku.kb.eval.metrics.MetricModel;
import com.zhishiku.kb.eval.metrics.MetricModel.CaseMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.GroupMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.MetricValue;
import com.zhishiku.kb.eval.metrics.MetricModel.RunMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 显式选择基线，并保留候选减基线的原始差值。
 */
public final class ComparisonCalculator {
    /**
     * 一项对比，两侧适用数保留。
     *
     * @param metric                 指标
     * @param k                      分块预算
     * @param baseline               基线值
     * @param candidate              候选值
     * @param rawDelta               候选减基线；任一侧为空则为空
     * @param baselineEligibleCases  基线适用数
     * @param candidateEligibleCases 候选适用数
     */
    public record Delta(Metric metric, Integer k, Double baseline, Double candidate, Double rawDelta,
                        int baselineEligibleCases, int candidateEligibleCases) {
    }

    /**
     * 分类或逐场景差值。
     *
     * @param id     分类或场景标识
     * @param deltas 各项差值
     */
    public record SeriesDelta(String id, List<Delta> deltas) {
        public SeriesDelta {
            deltas = List.copyOf(deltas);
        }
    }

    /**
     * 按改善方向识别的退步场景。
     *
     * @param metric  指标
     * @param k       分块预算
     * @param caseIds 稳定排序场景标识
     */
    public record Regression(Metric metric, Integer k, List<String> caseIds) {
        public Regression {
            caseIds = List.copyOf(caseIds);
        }
    }

    /**
     * 候选与基线比较。
     *
     * @param baselineId  基线标识
     * @param candidateId 候选标识
     * @param overall     总体差值
     * @param groups      分类差值
     * @param cases       逐场景差值
     * @param regressions 按指标列出的退步用例
     */
    public record Comparison(String baselineId, String candidateId, List<Delta> overall,
                             List<SeriesDelta> groups, List<SeriesDelta> cases, List<Regression> regressions) {
        public Comparison {
            overall = List.copyOf(overall);
            groups = List.copyOf(groups);
            cases = List.copyOf(cases);
            regressions = List.copyOf(regressions);
        }
    }

    public List<Comparison> compare(List<RunMetrics> runs, String baselineId) {
        if (baselineId == null) {
            return List.of();
        }
        RunMetrics baseline = runs.stream().filter(run -> run.run().runId().equals(baselineId)).findFirst()
                .orElseThrow(ComparisonCalculator::invalidBaseline);
        if (runs.size() < 2) {
            throw invalidBaseline();
        }
        return runs.stream().filter(run -> !run.run().runId().equals(baselineId))
                .sorted(Comparator.comparing(run -> run.run().runId()))
                .map(candidate -> compare(baseline, candidate)).toList();
    }

    private Comparison compare(RunMetrics baseline, RunMetrics candidate) {
        List<SeriesDelta> groups = new ArrayList<>();
        for (GroupMetrics group : candidate.groups()) {
            GroupMetrics reference =
                    baseline.groups().stream().filter(value -> value.tag().equals(group.tag())).findFirst().orElseThrow();
            groups.add(new SeriesDelta(group.tag(), deltas(reference.metrics(), group.metrics())));
        }
        List<SeriesDelta> cases = new ArrayList<>();
        for (CaseMetrics sample : candidate.cases()) {
            CaseMetrics reference =
                    baseline.cases().stream().filter(value -> value.caseId().equals(sample.caseId())).findFirst().orElseThrow();
            cases.add(new SeriesDelta(sample.caseId(), deltas(reference.metrics(), sample.metrics())));
        }
        List<Regression> regressions = new ArrayList<>();
        for (MetricValue metric : candidate.overall().metrics()) {
            List<String> ids = cases.stream().filter(sample -> sample.deltas().stream().anyMatch(delta ->
                            delta.metric() == metric.metric() && Objects.equals(delta.k(), metric.k())
                                    && delta.rawDelta() != null && (metric.metric().higherIsBetter() ?
                                    delta.rawDelta() < 0 : delta.rawDelta() > 0)))
                    .map(SeriesDelta::id).sorted().toList();
            if (!ids.isEmpty()) {
                regressions.add(new Regression(metric.metric(), metric.k(), ids));
            }
        }
        return new Comparison(baseline.run().runId(), candidate.run().runId(),
                deltas(baseline.overall().metrics(), candidate.overall().metrics()), groups, cases, regressions);
    }

    private static List<Delta> deltas(List<MetricValue> baseline, List<MetricValue> candidate) {
        return candidate.stream().map(value -> {
            MetricValue reference = MetricModel.find(baseline, value.metric(), value.k());
            Double difference = reference.value() == null || value.value() == null ? null :
                    value.value() - reference.value();
            return new Delta(value.metric(), value.k(), reference.value(), value.value(), difference,
                    reference.eligibleCases(), value.eligibleCases());
        }).toList();
    }

    private static EvaluationException invalidBaseline() {
        return new EvaluationException(Code.INVALID_BASELINE, "cli", 0, "baseline");
    }
}
