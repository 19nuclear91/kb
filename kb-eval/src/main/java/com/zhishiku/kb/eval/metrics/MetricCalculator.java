package com.zhishiku.kb.eval.metrics;

import com.zhishiku.kb.eval.metrics.MetricModel.CaseMetrics;
import com.zhishiku.kb.eval.metrics.MetricModel.MetricValue;
import com.zhishiku.kb.eval.model.InputModel.Hit;
import com.zhishiku.kb.eval.model.InputModel.LabelCase;
import com.zhishiku.kb.eval.model.InputModel.ResultCase;
import com.zhishiku.kb.eval.model.InputModel.VersionKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 先截断分块，再按版本判定；重复位置仍消耗预算。
 */
public final class MetricCalculator {
    public CaseMetrics calculate(LabelCase label, ResultCase result, List<Integer> ks) {
        List<MetricValue> values = new ArrayList<>();
        long relevant = label.relevance().values().stream().filter(grade -> grade > 0).count();
        List<Integer> idealGrades = label.relevance().values().stream().sorted(Comparator.reverseOrder()).toList();
        for (int k : ks.stream().sorted().toList()) {
            int n = Math.min(k, result.hits().size());
            Set<VersionKey> versions = new HashSet<>();
            int relevantHits = 0;
            double reciprocal = 0;
            double dcg = 0;
            for (int i = 0; i < n; i++) {
                Hit hit = result.hits().get(i);
                if (versions.add(hit.version())) {
                    int grade = label.relevance().getOrDefault(hit.version(), 0);
                    if (grade > 0) {
                        relevantHits++;
                        if (reciprocal == 0) {
                            reciprocal = 1.0 / (i + 1);
                        }
                        dcg += gain(grade, i + 1);
                    }
                }
            }
            double idcg = 0;
            for (int i = 0; i < Math.min(k, idealGrades.size()); i++) {
                idcg += gain(idealGrades.get(i), i + 1);
            }
            values.add(value(Metric.HIT, k, relevant > 0 ? (relevantHits > 0 ? 1.0 : 0.0) : null));
            values.add(value(Metric.RECALL, k, relevant > 0 ? (double) relevantHits / relevant : null));
            values.add(value(Metric.MRR, k, relevant > 0 ? reciprocal : null));
            values.add(value(Metric.NDCG, k, relevant > 0 ? dcg / idcg : null));
            values.add(value(Metric.DUPLICATE_RATE, k, n > 0 ? (double) (n - versions.size()) / n : null));
        }
        values.add(value(Metric.EMPTY_RESULT_ACCURACY, null,
                label.expectedEmpty() ? (result.totalHits() == 0 ? 1.0 : 0.0) : null));
        return new CaseMetrics(label.caseId(), label.tags(), values);
    }

    private static double gain(int grade, int rank) {
        return (Math.pow(2, grade) - 1) / (Math.log(rank + 1.0) / Math.log(2));
    }

    private static MetricValue value(Metric metric, Integer k, Double value) {
        return new MetricValue(metric, k, value, value == null ? 0 : 1);
    }
}
