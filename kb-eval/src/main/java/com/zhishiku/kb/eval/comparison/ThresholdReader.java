package com.zhishiku.kb.eval.comparison;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.EvaluationException.Code;
import com.zhishiku.kb.eval.input.JsonInput;
import com.zhishiku.kb.eval.input.JsonInput.Document;
import com.zhishiku.kb.eval.metrics.Metric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 显式总体质量门禁配置，不内置生产门槛。
 */
public final class ThresholdReader {
    /**
     * 单项总体阈值。
     *
     * @param metric        指标
     * @param k             分块预算；空结果符合率为空
     * @param min           质量下限
     * @param max           重复率上限
     * @param maxRegression 改善方向上的最大绝对退步量
     */
    public record Rule(Metric metric, Integer k, Double min, Double max, Double maxRegression) {
    }

    /**
     * 已验证配置。
     *
     * @param rules  按指标和 k 排序的规则
     * @param sha256 配置实际字节摘要
     */
    public record Thresholds(List<Rule> rules, String sha256) {
        public Thresholds {
            rules = List.copyOf(rules);
        }
    }

    public Thresholds read(String file, List<Integer> ks, String baselineId) {
        Document document = JsonInput.readDocument(file, "thresholds");
        JsonInput.fields(document.object(), Set.of("schemaVersion", "rules"), "thresholds", 0);
        JsonInput.schema(document.object(), "thresholds");
        JsonNode nodes = JsonInput.required(document.object(), "rules", "thresholds", 0);
        if (!nodes.isArray() || nodes.isEmpty()) {
            throw invalid("rules");
        }
        List<Rule> rules = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode node : nodes) {
            JsonInput.fields(node, Set.of("metric", "k", "min", "max", "maxRegression"), "thresholds", 0);
            Metric metric = Metric.fromKey(JsonInput.text(node, "metric", "thresholds", 0));
            Integer k = null;
            if (metric == Metric.EMPTY_RESULT_ACCURACY) {
                if (node.has("k")) {
                    throw invalid("k");
                }
            } else {
                JsonNode raw = JsonInput.required(node, "k", "thresholds", 0);
                if (!raw.isIntegralNumber() || !raw.canConvertToInt() || !ks.contains(raw.intValue())) {
                    throw invalid("k");
                }
                k = raw.intValue();
            }
            Double min = limit(node, "min");
            Double max = limit(node, "max");
            Double regression = limit(node, "maxRegression");
            if (min == null && max == null && regression == null) {
                throw invalid("rules");
            }
            if ((metric.higherIsBetter() && max != null) || (!metric.higherIsBetter() && min != null)) {
                throw invalid("metric");
            }
            if (regression != null && baselineId == null) {
                throw invalid("maxRegression");
            }
            if (!unique.add(metric.key() + ":" + k)) {
                throw invalid("rules");
            }
            rules.add(new Rule(metric, k, min, max, regression));
        }
        rules.sort((left, right) -> {
            int metric = left.metric().compareTo(right.metric());
            return metric != 0 ? metric : Integer.compare(left.k() == null ? 0 : left.k(), right.k() == null ? 0 :
                    right.k());
        });
        return new Thresholds(rules, document.sha256());
    }

    private static Double limit(JsonNode node, String field) {
        if (!node.has(field)) {
            return null;
        }
        double value = JsonInput.finite(node, field, "thresholds", 0);
        if (value < 0 || value > 1) {
            throw invalid(field);
        }
        return value;
    }

    private static EvaluationException invalid(String field) {
        return new EvaluationException(Code.INVALID_THRESHOLD, "thresholds", 0, field);
    }
}
