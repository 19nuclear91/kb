package com.zhishiku.kb.answereval;

import java.util.HashSet;
import java.util.List;

/** 阈值只来自显式配置；同一配置供复核和报告使用。 */
public record ReportConfig(int schemaVersion, Policy reviewPolicy, List<Rule> rules) {
    public record Policy(String version, double sampleProportion) {
        public Policy {
            Checks.require("stratified-sha256-v1".equals(version) && Double.isFinite(sampleProportion)
                    && sampleProportion > 0 && sampleProportion <= 1);
        }
    }

    public record Rule(String tag, String metric, Double min, Double maxRegression) {
        public Rule {
            if (tag != null) {
                Checks.id(tag);
            }
            Checks.require(Metrics.NAMES.contains(metric) && (min != null || maxRegression != null));
            for (Double threshold : new Double[]{min, maxRegression}) {
                Checks.require(threshold == null || Double.isFinite(threshold) && threshold >= 0 && threshold <= 1);
            }
        }
    }

    public ReportConfig {
        Checks.require(schemaVersion == 1 && reviewPolicy != null);
        rules = List.copyOf(rules);
        var unique = new HashSet<String>();
        rules.forEach(rule -> Checks.require(unique.add((rule.tag() == null ? "*" : rule.tag()) + "/" + rule.metric())));
    }

    public String policySha256() {
        return StrictJson.sha(StrictJson.bytes(reviewPolicy));
    }
}
