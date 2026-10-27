package com.zhishiku.kb.eval.metrics;

import com.zhishiku.kb.eval.metrics.MetricModel.CaseMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.GroupMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.LatencyStats;
import com.zhishiku.kb.eval.metrics.MetricModel.MetricValue;
import com.zhishiku.kb.eval.metrics.MetricModel.RunMetrics;
import com.zhishiku.kb.eval.model.InputModel.Dataset;
import com.zhishiku.kb.eval.model.InputModel.Run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 按稳定顺序计算宏平均，多标签只在分类中重复计数。
 */
public final class Aggregator {
    public RunMetrics calculate(Dataset dataset, Run run, List<Integer> ks) {
        MetricCalculator calculator = new MetricCalculator();
        List<CaseMetrics> cases = dataset.cases().values().stream()
                .sorted(Comparator.comparing(label -> label.caseId()))
                .map(label -> calculator.calculate(label, run.cases().get(label.caseId()), ks)).toList();
        Map<String, List<CaseMetrics>> groups = new TreeMap<>();
        for (CaseMetrics sample : cases) {
            List<String> tags = sample.tags().isEmpty() ? List.of("UNTAGGED") : sample.tags();
            for (String tag : tags) {
                groups.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(sample);
            }
        }
        return new RunMetrics(run, aggregate(null, cases), groups.entrySet().stream()
                .map(group -> aggregate(group.getKey(), group.getValue())).toList(), cases, latency(run));
    }

    public GroupMetrics aggregate(String tag, List<CaseMetrics> samples) {
        List<CaseMetrics> sorted = samples.stream().sorted(Comparator.comparing(CaseMetrics::caseId)).toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("No samples to aggregate");
        }
        List<MetricValue> values = new ArrayList<>();
        for (MetricValue template : sorted.getFirst().metrics()) {
            List<Double> eligible = sorted.stream().map(sample -> MetricModel.find(sample.metrics(),
                            template.metric(), template.k()).value())
                    .filter(value -> value != null).toList();
            Double mean = eligible.isEmpty() ? null :
                    eligible.stream().mapToDouble(value -> value / eligible.size()).sum();
            values.add(new MetricValue(template.metric(), template.k(), mean, eligible.size()));
        }
        return new GroupMetrics(tag, sorted.size(), values);
    }

    public LatencyStats latency(Run run) {
        List<Double> values = run.cases().values().stream().map(sample -> sample.latencyMs())
                .filter(value -> value != null).sorted().toList();
        if (values.isEmpty()) {
            return new LatencyStats(run.cases().size(), 0, null, null);
        }
        // 先按最大值缩放，避免大数求和溢出和极小值逐项除以样本数时归零。
        double maximum = values.getLast();
        double mean = maximum == 0 ? 0
                :
                Math.min(1.0, values.stream().mapToDouble(value -> value / maximum).average().orElseThrow()) * maximum;
        return new LatencyStats(run.cases().size(), values.size(), mean,
                values.get((int) Math.ceil(0.95 * values.size()) - 1));
    }
}
