package com.zhishiku.kb.eval.metrics;

import com.zhishiku.kb.eval.model.InputModel.Run;

import java.util.List;
import java.util.Objects;

/**
 * 纯计算组件间的数据结构。
 */
public final class MetricModel {
    private MetricModel() {
    }

    /**
     * 单项指标及适用分母。
     *
     * @param metric        指标
     * @param k             分块预算；空结果符合率为空
     * @param value         未舍入值；不适用时为空
     * @param eligibleCases 适用样本数
     */
    public record MetricValue(Metric metric, Integer k, Double value, int eligibleCases) {
    }

    /**
     * 单场景指标。
     *
     * @param caseId  场景标识
     * @param tags    稳定排序分类
     * @param metrics 指标列表
     */
    public record CaseMetrics(String caseId, List<String> tags, List<MetricValue> metrics) {
        public CaseMetrics {
            tags = tags.stream().sorted().toList();
            metrics = List.copyOf(metrics);
        }
    }

    /**
     * 总体或分类宏平均。
     *
     * @param tag        分类；总体为空
     * @param totalCases 分组样本数
     * @param metrics    以适用样本为分母的指标
     */
    public record GroupMetrics(String tag, int totalCases, List<MetricValue> metrics) {
        public GroupMetrics {
            metrics = List.copyOf(metrics);
        }
    }

    /**
     * 文件声明耗时统计。
     *
     * @param totalCases    样本总数
     * @param recordedCases 合法耗时覆盖数
     * @param meanMs        均值；无记录时为空
     * @param p95Ms         nearest-rank P95；无记录时为空
     */
    public record LatencyStats(int totalCases, int recordedCases, Double meanMs, Double p95Ms) {
    }

    /**
     * 一个运行的全部计算。
     *
     * @param run     受校验输入身份
     * @param overall 总体指标
     * @param groups  标签指标
     * @param cases   按 caseId 排序的指标
     * @param latency 附加耗时
     */
    public record RunMetrics(Run run, GroupMetrics overall, List<GroupMetrics> groups,
                             List<CaseMetrics> cases, LatencyStats latency) {
        public RunMetrics {
            groups = List.copyOf(groups);
            cases = List.copyOf(cases);
        }
    }

    public static MetricValue find(List<MetricValue> values, Metric metric, Integer k) {
        return values.stream().filter(value -> value.metric() == metric && Objects.equals(value.k(), k))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Metric not calculated"));
    }
}
