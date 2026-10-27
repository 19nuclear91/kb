package com.zhishiku.kb.eval.report;

import com.zhishiku.kb.eval.comparison.ComparisonCalculator.Comparison;
import com.zhishiku.kb.eval.comparison.ComparisonCalculator.Delta;
import com.zhishiku.kb.eval.comparison.ComparisonCalculator.SeriesDelta;
import com.zhishiku.kb.eval.metrics.MetricModel.CaseMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.GroupMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.MetricValue;
import com.zhishiku.kb.eval.report.EvaluationReport.RunSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 长表 CSV，各行保留当前值、基线值、原始差和适用数。
 */
public final class CsvReportWriter {
    public String summary(EvaluationReport report) {
        StringBuilder csv = new StringBuilder();
        row(csv, List.of("runId", "sourceType", "scope", "tag", "totalCases", "metric", "k", "eligibleCases", "value",
                "baselineId", "baselineEligibleCases", "baseline", "rawDelta"));
        for (RunSummary run : report.runs()) {
            Comparison comparison = comparison(report, run.runId());
            List<GroupMetrics> groups = new ArrayList<>();
            groups.add(run.overall());
            groups.addAll(run.groups());
            for (GroupMetrics group : groups) {
                List<Delta> deltas = comparison == null ? List.of() : group.tag() == null ? comparison.overall()
                        : series(comparison.groups(), group.tag());
                for (MetricValue value : group.metrics()) {
                    Delta delta = delta(deltas, value);
                    row(csv, List.of(run.runId(), run.sourceType().name(), group.tag() == null ? "OVERALL" : "TAG",
                            text(group.tag()),
                            Integer.toString(group.totalCases()), value.metric().key(), text(value.k()),
                            Integer.toString(value.eligibleCases()),
                            number(value.value()), comparison == null ? "" : comparison.baselineId(),
                            delta == null ? "" : Integer.toString(delta.baselineEligibleCases()),
                            delta == null ? "" : number(delta.baseline()), delta == null ? "" :
                                    number(delta.rawDelta())));
                }
            }
        }
        return csv.toString();
    }

    public String cases(EvaluationReport report) {
        StringBuilder csv = new StringBuilder();
        row(csv, List.of("runId", "caseId", "tags", "metric", "k", "eligibleCases", "value", "baselineId", "baseline"
                , "rawDelta"));
        for (RunSummary run : report.runs()) {
            Comparison comparison = comparison(report, run.runId());
            for (CaseMetrics sample : run.cases()) {
                List<Delta> deltas = comparison == null ? List.of() : series(comparison.cases(), sample.caseId());
                for (MetricValue value : sample.metrics()) {
                    Delta delta = delta(deltas, value);
                    row(csv, List.of(run.runId(), sample.caseId(), String.join(";", sample.tags()),
                            value.metric().key(), text(value.k()),
                            Integer.toString(value.eligibleCases()), number(value.value()), comparison == null ? "" :
                                    comparison.baselineId(),
                            delta == null ? "" : number(delta.baseline()), delta == null ? "" :
                                    number(delta.rawDelta())));
                }
            }
        }
        return csv.toString();
    }

    private static Comparison comparison(EvaluationReport report, String runId) {
        return report.comparisons().stream().filter(value -> value.candidateId().equals(runId)).findFirst().orElse(null);
    }

    private static List<Delta> series(List<SeriesDelta> values, String id) {
        return values.stream().filter(value -> value.id().equals(id)).findFirst().map(SeriesDelta::deltas).orElse(List.of());
    }

    private static Delta delta(List<Delta> values, MetricValue metric) {
        return values.stream().filter(value -> value.metric() == metric.metric() && Objects.equals(value.k(),
                metric.k())).findFirst().orElse(null);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public static String number(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    public static String escape(String field) {
        return field.indexOf(',') >= 0 || field.indexOf('"') >= 0 || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0
                ? "\"" + field.replace("\"", "\"\"") + "\"" : field;
    }

    private static void row(StringBuilder target, List<String> values) {
        target.append(String.join(",", values.stream().map(CsvReportWriter::escape).toList())).append("\r\n");
    }
}
