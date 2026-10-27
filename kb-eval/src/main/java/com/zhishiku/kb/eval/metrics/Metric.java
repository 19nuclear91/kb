package com.zhishiku.kb.eval.metrics;

import com.fasterxml.jackson.annotation.JsonValue;
import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.EvaluationException.Code;

/**
 * 指标键和改善方向。
 */
public enum Metric {
    HIT("hit", true), RECALL("recall", true), MRR("mrr", true), NDCG("ndcg", true),
    DUPLICATE_RATE("duplicateRate", false), EMPTY_RESULT_ACCURACY("emptyResultAccuracy", true);

    private final String key;
    private final boolean higherIsBetter;

    Metric(String key, boolean higherIsBetter) {
        this.key = key;
        this.higherIsBetter = higherIsBetter;
    }

    @JsonValue
    public String key() {
        return key;
    }

    public boolean higherIsBetter() {
        return higherIsBetter;
    }

    public static Metric fromKey(String key) {
        for (Metric metric : values()) {
            if (metric.key.equals(key)) {
                return metric;
            }
        }
        throw new EvaluationException(Code.INVALID_THRESHOLD, "thresholds", 0, "metric");
    }
}
