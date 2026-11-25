package com.zhishiku.kb.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 受控配置可收紧限额，不能超过公开契约的上界。 */
@ConfigurationProperties("kb.authorization-diagnostics")
public record DiagnosticLimits(
        @DefaultValue("65536")
        int bodyBytes,
        @DefaultValue("4096")
        int principalKeys,
        @DefaultValue("1000")
        int sources,
        @DefaultValue("524288")
        int responseBytes,
        @DefaultValue("5000")
        int budgetMillis
) {
    public DiagnosticLimits {
        if (bodyBytes < 1 || bodyBytes > 65536 || principalKeys < 1 || principalKeys > 4096
                || sources < 1 || sources > 1000 || responseBytes < 1 || responseBytes > 524288
                || budgetMillis < 1 || budgetMillis > 5000) {
            throw new IllegalArgumentException("诊断限额必须为正数且不超过公开契约上限");
        }
    }

    public static DiagnosticLimits defaults() {
        return new DiagnosticLimits(65536, 4096, 1000, 524288, 5000);
    }
}
