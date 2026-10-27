package com.zhishiku.kb.eval.comparison;

import com.zhishiku.kb.eval.comparison.ThresholdReader.Rule;
import com.zhishiku.kb.eval.comparison.ThresholdReader.Thresholds;
import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.EvaluationException.Code;
import com.zhishiku.kb.eval.metrics.Metric;
import com.zhishiku.kb.eval.metrics.MetricModel;
import com.zhishiku.kb.eval.metrics.MetricModel.RunMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 使用未展示舍入值判定，计算容差固定为 1e-12。
 */
public final class QualityGate {
    private static final double TOLERANCE = 1e-12;

    /**
     * 可复核的规则判定。
     *
     * @param runId              被判定运行
     * @param metric             指标
     * @param k                  分块预算
     * @param actual             未舍入实际值
     * @param baseline           基线参考值；未使用退步限制时为空
     * @param min                质量下限
     * @param max                重复率上限
     * @param maxRegression      退步上限
     * @param observedRegression 实际绝对退步量
     * @param passed             是否全部限制通过
     */
    public record RuleResult(String runId, Metric metric, Integer k, double actual, Double baseline,
                             Double min, Double max, Double maxRegression, Double observedRegression, boolean passed) {
    }

    /**
     * 门禁结果，未配置时仍视为成功。
     *
     * @param configured 是否配置规则
     * @param passed     是否通过
     * @param rules      全部判定，不因首项失败提前退出
     */
    public record GateOutcome(boolean configured, boolean passed, List<RuleResult> rules) {
        public GateOutcome {
            rules = List.copyOf(rules);
        }
    }

    public GateOutcome evaluate(List<RunMetrics> runs, String baselineId, Thresholds thresholds) {
        if (thresholds == null) {
            return new GateOutcome(false, true, List.of());
        }
        RunMetrics baseline = baselineId == null ? null : runs.stream()
                .filter(run -> run.run().runId().equals(baselineId)).findFirst()
                .orElseThrow(() -> new EvaluationException(Code.INVALID_BASELINE, "cli", 0, "baseline"));
        List<RunMetrics> candidates =
                runs.stream().filter(run -> baselineId == null || !run.run().runId().equals(baselineId))
                .sorted(Comparator.comparing(run -> run.run().runId())).toList();
        if (candidates.isEmpty()) {
            throw new EvaluationException(Code.INVALID_BASELINE, "cli", 0, "baseline");
        }
        List<RuleResult> results = new ArrayList<>();
        for (RunMetrics candidate : candidates) {
            for (Rule rule : thresholds.rules()) {
                Double actual = MetricModel.find(candidate.overall().metrics(), rule.metric(), rule.k()).value();
                Double reference = rule.maxRegression() == null || baseline == null ? null
                        : MetricModel.find(baseline.overall().metrics(), rule.metric(), rule.k()).value();
                if (actual == null || (rule.maxRegression() != null && reference == null)) {
                    throw new EvaluationException(Code.INVALID_THRESHOLD, "thresholds", 0, "metric");
                }
                Double regression = reference == null ? null : rule.metric().higherIsBetter() ? reference - actual :
                        actual - reference;
                boolean passed = (rule.min() == null || actual + TOLERANCE >= rule.min())
                        && (rule.max() == null || actual <= rule.max() + TOLERANCE)
                        && (rule.maxRegression() == null || regression <= rule.maxRegression() + TOLERANCE);
                results.add(new RuleResult(candidate.run().runId(), rule.metric(), rule.k(), actual, reference,
                        rule.min(), rule.max(), rule.maxRegression(), regression, passed));
            }
        }
        return new GateOutcome(true, results.stream().allMatch(RuleResult::passed), results);
    }
}
