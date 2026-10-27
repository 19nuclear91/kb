package com.zhishiku.kb.eval.report;

import com.zhishiku.kb.eval.comparison.ComparisonCalculator.Comparison;
import com.zhishiku.kb.eval.comparison.QualityGate.GateOutcome;
import com.zhishiku.kb.eval.comparison.ThresholdReader.Thresholds;
import com.zhishiku.kb.eval.metrics.MetricModel.RunMetrics;
import com.zhishiku.kb.eval.model.InputModel.Dataset;
import com.zhishiku.kb.eval.report.EvaluationReport.DatasetSummary;
import com.zhishiku.kb.eval.report.EvaluationReport.RunSummary;
import com.zhishiku.kb.eval.report.EvaluationReport.Scope;

import java.util.List;

/**
 * 从受控指标结构投影到报告，不向序列化器传入 Run.cases。
 */
public final class ReportFactory {
    public EvaluationReport create(Dataset dataset, List<Integer> ks, List<RunMetrics> runs, String baselineId,
                                   List<Comparison> comparisons, Thresholds thresholds, GateOutcome gate) {
        List<RunSummary> summaries = runs.stream().map(run -> new RunSummary(run.run().runId(),
                run.run().configurationId(),
                run.run().sourceType(), run.run().manifestSha256(), run.run().resultsSha256(),
                run.overall(), run.groups(), run.cases(), run.latency())).toList();
        return new EvaluationReport(1, 1, Scope.offline(), new DatasetSummary(dataset.identity(),
                dataset.manifestSha256(),
                dataset.labelsSha256(), dataset.cases().size()), ks, baselineId, thresholds == null ? null :
                thresholds.sha256(),
                summaries, comparisons, gate);
    }
}
