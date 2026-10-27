package com.zhishiku.kb.eval.report;

import com.zhishiku.kb.eval.comparison.ComparisonCalculator.Comparison;
import com.zhishiku.kb.eval.comparison.ComparisonCalculator.Delta;
import com.zhishiku.kb.eval.comparison.ComparisonCalculator.Regression;
import com.zhishiku.kb.eval.comparison.ComparisonCalculator.SeriesDelta;
import com.zhishiku.kb.eval.comparison.QualityGate.GateOutcome;
import com.zhishiku.kb.eval.comparison.QualityGate.RuleResult;
import com.zhishiku.kb.eval.metrics.Metric;
import com.zhishiku.kb.eval.metrics.MetricModel.CaseMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.GroupMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.LatencyStats;
import com.zhishiku.kb.eval.metrics.MetricModel.MetricValue;
import com.zhishiku.kb.eval.model.InputModel.Identity;
import com.zhishiku.kb.eval.model.InputModel.SourceType;
import com.zhishiku.kb.eval.report.EvaluationReport.DatasetSummary;
import com.zhishiku.kb.eval.report.EvaluationReport.RunSummary;
import com.zhishiku.kb.eval.report.EvaluationReport.Scope;

import java.util.List;

/**
 * 序列化组件使用的内存数据，不读取样例或执行 CLI。
 */
final class ReportFixture {
    private ReportFixture() {
    }

    static EvaluationReport report() {
        RunSummary candidate = run("z_candidate", SourceType.RECORDED, 2.0 / 3);
        RunSummary baseline = run("a_base", SourceType.SYNTHETIC, 0.8);
        var delta = new Delta(Metric.RECALL, 5, 0.8, 2.0 / 3, 2.0 / 3 - 0.8, 1, 1);
        var missing = new Delta(Metric.DUPLICATE_RATE, 5, null, null, null, 0, 0);
        var comparison = new Comparison("a_base", "z_candidate", List.of(delta, missing),
                List.of(new SeriesDelta("a_tag", List.of(delta))), List.of(new SeriesDelta("c1", List.of(delta))),
                List.of(new Regression(Metric.RECALL, 5, List.of("c1"))));
        var gate = new GateOutcome(true, false, List.of(new RuleResult("z_candidate", Metric.RECALL, 5, 2.0 / 3,
                0.8, 0.7, null, 0.02, 0.8 - 2.0 / 3, false)));
        return new EvaluationReport(1, 1, Scope.offline(), new DatasetSummary(new Identity("demo", "v1", "s1"),
                "a".repeat(64), "b".repeat(64), 2), List.of(10, 5), "a_base", "e".repeat(64),
                List.of(candidate, baseline), List.of(comparison), gate);
    }

    static RunSummary run(String id, SourceType source, double recall) {
        var applicable = new MetricValue(Metric.RECALL, 5, recall, 1);
        var missing = new MetricValue(Metric.DUPLICATE_RATE, 5, null, 0);
        var empty = new MetricValue(Metric.EMPTY_RESULT_ACCURACY, null, 1.0, 1);
        var metrics = List.of(applicable, missing, empty);
        var cases = List.of(new CaseMetrics("c2", List.of("z_tag"), List.of(new MetricValue(Metric.RECALL, 5, null,
                        0), missing, empty)),
                new CaseMetrics("c1", List.of("a_tag"), List.of(applicable, missing,
                        new MetricValue(Metric.EMPTY_RESULT_ACCURACY, null, null, 0))));
        return new RunSummary(id, "config_v1", source, "c".repeat(64), "d".repeat(64), new GroupMetrics(null, 2,
                metrics),
                List.of(new GroupMetrics("z_tag", 1, cases.getFirst().metrics()), new GroupMetrics("a_tag", 1,
                        cases.getLast().metrics())),
                cases, new LatencyStats(2, 1, 12.5, 12.5));
    }
}
