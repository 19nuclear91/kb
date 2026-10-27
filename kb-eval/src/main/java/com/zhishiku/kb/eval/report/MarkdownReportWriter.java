package com.zhishiku.kb.eval.report;

import com.zhishiku.kb.eval.comparison.ComparisonCalculator.Delta;
import com.zhishiku.kb.eval.metrics.MetricModel.GroupMetrics;

import java.util.List;

/**
 * 人读汇总和退步场景，呈现舍入不影响计算和门禁。
 */
public final class MarkdownReportWriter {
    public String render(EvaluationReport report) {
        StringBuilder text = new StringBuilder("# 离线检索质量报告\n\n");
        text.append("范围：仅离线文件计算，按文档版本判定相关性，原始分块占排名预算。来源和配置均为文件声明，未验证实际算法、实时权限、片段是否包含答案或线上检索质量。耗时仅为输入记录。\n\n")
                .append("格式版本：").append(report.schemaVersion()).append("；指标口径版本：").append(report.metricDefinitionVersion()).append("。\n\n")
                .append("数据集：").append(escape(report.dataset().identity().datasetId())).append(" / ")
                .append(escape(report.dataset().identity().datasetVersion())).append("；语料快照：")
                .append(escape(report.dataset().identity().corpusSnapshotId())).append("；样本数：").append(report.dataset().totalCases()).append("。\n\n")
                .append("数据集清单 SHA-256：`").append(report.dataset().manifestSha256()).append("`\n\n")
                .append("标注 SHA-256：`").append(report.dataset().labelsSha256()).append("`\n\n");
        if (report.thresholdsSha256() != null) {
            text.append("阈值 SHA-256：`").append(report.thresholdsSha256()).append("`\n\n");
        }
        for (var run : report.runs()) {
            text.append("## ").append(escape(run.runId())).append("\n\n来源声明：").append(run.sourceType().name())
                    .append("；配置声明：").append(escape(run.configurationId())).append("。\n\n")
                    .append("运行清单 SHA-256：`").append(run.manifestSha256()).append("`\n\n")
                    .append("结果 SHA-256：`").append(run.resultsSha256()).append("`\n\n");
            group(text, run.overall());
            for (GroupMetrics group : run.groups()) {
                group(text, group);
            }
            text.append("输入耗时覆盖：").append(run.latency().recordedCases()).append("/").append(run.latency().totalCases())
                    .append("；均值 ms：").append(number(run.latency().meanMs())).append("；P95 ms：")
                    .append(number(run.latency().p95Ms())).append("。\n\n");
        }
        for (var comparison : report.comparisons()) {
            text.append("## 比较：").append(escape(comparison.candidateId())).append(" / 基线 ")
                    .append(escape(comparison.baselineId())).append("\n\n差值为候选减基线；质量指标上升、重复率下降表示改善。\n\n");
            deltas(text, "总体", comparison.overall());
            for (var group : comparison.groups()) {
                deltas(text, "分类 " + escape(group.id()), group.deltas());
            }
            text.append("### 退步场景\n\n");
            if (comparison.regressions().isEmpty()) {
                text.append("无适用指标退步。\n\n");
            }
            for (var regression : comparison.regressions()) {
                text.append("- ").append(regression.metric().key()).append(regression.k() == null ? "" :
                                "@" + regression.k())
                        .append(": ").append(String.join(", ",
                                regression.caseIds().stream().map(MarkdownReportWriter::escape).toList())).append("\n");
            }
            text.append("\n");
        }
        text.append("## 门禁\n\n").append(report.gate().configured() ? (report.gate().passed() ? "通过。" : "失败；完整报告保留。")
                        : "未配置生产质量门槛。")
                .append("\n\n");
        if (report.gate().configured()) {
            text.append("| 运行 | 指标 | k | 实际值 | 基线 | min | max | 最大退步 | 实际退步 | 结果 |\n")
                    .append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
            for (var rule : report.gate().rules()) {
                text.append("| ").append(escape(rule.runId())).append(" | ").append(rule.metric().key()).append(" | ")
                        .append(rule.k() == null ? "N/A" : rule.k()).append(" | ").append(number(rule.actual())).append(" | ")
                        .append(number(rule.baseline())).append(" | ").append(number(rule.min())).append(" | ").append(number(rule.max()))
                        .append(" | ").append(number(rule.maxRegression())).append(" | ").append(number(rule.observedRegression()))
                        .append(" | ").append(rule.passed() ? "pass" : "fail").append(" |\n");
            }
            text.append("\n判定使用未舍入值，最多 1e-12 计算容差；展示六位小数不能替代 JSON 原始值。\n");
        }
        return text.toString();
    }

    private static void group(StringBuilder text, GroupMetrics group) {
        text.append("### ").append(group.tag() == null ? "总体" : "分类 " + escape(group.tag())).append("\n\n样本数：")
                .append(group.totalCases()).append("。\n\n| 指标 | k | 值 | 适用样本数 |\n| --- | --- | --- | --- |\n");
        for (var value : group.metrics()) {
            text.append("| ").append(value.metric().key()).append(" | ").append(value.k() == null ? "N/A" : value.k())
                    .append(" | ").append(number(value.value())).append(" | ").append(value.eligibleCases()).append(
                            " |\n");
        }
        text.append("\n");
    }

    private static void deltas(StringBuilder text, String title, List<Delta> values) {
        text.append("### ").append(title).append("\n\n| 指标 | k | 基线 | 候选 | rawDelta | 基线适用数 | 候选适用数 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (Delta value : values) {
            text.append("| ").append(value.metric().key()).append(" | ").append(value.k() == null ? "N/A" : value.k())
                    .append(" | ").append(number(value.baseline())).append(" | ").append(number(value.candidate()))
                    .append(" | ").append(number(value.rawDelta())).append(" | ").append(value.baselineEligibleCases())
                    .append(" | ").append(value.candidateEligibleCases()).append(" |\n");
        }
        text.append("\n");
    }

    private static String number(Double value) {
        return value == null ? "N/A" : CsvReportWriter.number(value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("]",
                "\\]");
    }
}
