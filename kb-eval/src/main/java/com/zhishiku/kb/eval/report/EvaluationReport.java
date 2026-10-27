package com.zhishiku.kb.eval.report;

import com.zhishiku.kb.eval.comparison.ComparisonCalculator.Comparison;
import com.zhishiku.kb.eval.comparison.QualityGate.GateOutcome;
import com.zhishiku.kb.eval.metrics.MetricModel.CaseMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.GroupMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.LatencyStats;
import com.zhishiku.kb.eval.model.InputModel.Identity;
import com.zhishiku.kb.eval.model.InputModel.SourceType;

import java.util.List;

/**
 * 报告根白名单，禁止加入原始输入树或文件路径。
 *
 * @param schemaVersion           报告格式版本
 * @param metricDefinitionVersion 指标口径版本
 * @param scope                   离线验证范围
 * @param dataset                 数据集身份与摘要
 * @param ks                      升序分块预算
 * @param baselineId              显式基线标识
 * @param thresholdsSha256        阈值摘要；未配置时为空
 * @param runs                    按 runId 排序的运行
 * @param comparisons             全部候选比较
 * @param gate                    完整门禁结果
 */
public record EvaluationReport(int schemaVersion, int metricDefinitionVersion, Scope scope,
                               DatasetSummary dataset, List<Integer> ks, String baselineId,
                               String thresholdsSha256, List<RunSummary> runs,
                               List<Comparison> comparisons, GateOutcome gate) {
    public EvaluationReport {
        ks = ks.stream().sorted().toList();
        runs = ReportOrdering.runs(runs);
        comparisons =
                comparisons.stream().sorted((left, right) -> left.candidateId().compareTo(right.candidateId())).toList();
    }

    /**
     * 明确未验证的线上行为。
     *
     * @param mode                    仅离线文件计算
     * @param relevanceUnit           相关性粒度
     * @param rankingUnit             排名预算粒度
     * @param algorithmsVerified      是否验证实际算法执行
     * @param livePermissionsVerified 是否验证实时权限
     * @param answerQualityVerified   是否验证答案或片段内容
     * @param latencySource           耗时来源
     */
    public record Scope(String mode, String relevanceUnit, String rankingUnit, boolean algorithmsVerified,
                        boolean livePermissionsVerified, boolean answerQualityVerified, String latencySource) {
        public static Scope offline() {
            return new Scope("OFFLINE_FILES_ONLY", "DOCUMENT_VERSION", "CHUNK", false, false, false,
                    "INPUT_RECORDED_ONLY");
        }
    }

    /**
     * 数据集可复核元数据。
     *
     * @param identity       数据集、版本和语料快照
     * @param manifestSha256 清单摘要
     * @param labelsSha256   标注摘要
     * @param totalCases     全部样本数
     */
    public record DatasetSummary(Identity identity, String manifestSha256, String labelsSha256, int totalCases) {
    }

    /**
     * 运行报告，不包含输入结果行。
     *
     * @param runId           运行标识
     * @param configurationId 配置声明
     * @param sourceType      输入来源声明
     * @param manifestSha256  清单摘要
     * @param resultsSha256   结果摘要
     * @param overall         总体宏平均
     * @param groups          分类宏平均
     * @param cases           逐场景指标
     * @param latency         输入声明耗时覆盖统计
     */
    public record RunSummary(String runId, String configurationId, SourceType sourceType,
                             String manifestSha256, String resultsSha256, GroupMetrics overall,
                             List<GroupMetrics> groups, List<CaseMetrics> cases, LatencyStats latency) {
        public RunSummary {
            groups = ReportOrdering.groups(groups);
            cases = ReportOrdering.cases(cases);
        }
    }
}
