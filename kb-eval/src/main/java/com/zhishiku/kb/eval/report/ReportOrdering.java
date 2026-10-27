package com.zhishiku.kb.eval.report;

import com.zhishiku.kb.eval.metrics.MetricModel.CaseMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.GroupMetrics;
import com.zhishiku.kb.eval.report.EvaluationReport.RunSummary;

import java.util.Comparator;
import java.util.List;

/**
 * 呈现前统一排序，保留输入计算值。
 */
public final class ReportOrdering {
    private ReportOrdering() {
    }

    public static List<RunSummary> runs(List<RunSummary> values) {
        return values.stream().sorted(Comparator.comparing(RunSummary::runId)).toList();
    }

    public static List<GroupMetrics> groups(List<GroupMetrics> values) {
        return values.stream().sorted(Comparator.comparing(GroupMetrics::tag)).toList();
    }

    public static List<CaseMetrics> cases(List<CaseMetrics> values) {
        return values.stream().sorted(Comparator.comparing(CaseMetrics::caseId)).toList();
    }
}
