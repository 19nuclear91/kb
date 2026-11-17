package com.zhishiku.kb.app.document.diff;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 对比资源边界；非法配置在属性绑定时阻止启动。 */
@ConfigurationProperties("kb.document-diff")
public record DocumentDiffProperties(
        @DefaultValue("500000") int maxCharsPerVersion,
        @DefaultValue("10000") int maxBlocksPerVersion,
        @DefaultValue("5000") long timeoutMs,
        @DefaultValue("4") int maxConcurrent,
        @DefaultValue("20000") int inlineDiffMaxChars,
        @DefaultValue("250000") int pairingMaxCells,
        @DefaultValue("0.3") double minSimilarity) {
    public DocumentDiffProperties {
        if (maxCharsPerVersion <= 0 || maxBlocksPerVersion <= 0 || timeoutMs <= 0
                || timeoutMs > Long.MAX_VALUE / 1_000_000 || maxConcurrent <= 0
                || inlineDiffMaxChars <= 0 || pairingMaxCells <= 0
                || !Double.isFinite(minSimilarity) || minSimilarity < 0 || minSimilarity > 1) {
            throw new IllegalArgumentException("kb.document-diff 配置超出允许范围");
        }
    }
}
