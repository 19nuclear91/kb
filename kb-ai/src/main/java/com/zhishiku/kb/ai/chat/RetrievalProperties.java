package com.zhishiku.kb.ai.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** AI 等待授权检索的独立预算，单位毫秒与字节。 */
@ConfigurationProperties("kb.ai.retrieval")
public record RetrievalProperties(@DefaultValue("10000") long timeoutMs,
                                  @DefaultValue("4194304") int maxResponseBytes) {
    public RetrievalProperties {
        if (timeoutMs <= 0 || timeoutMs > Long.MAX_VALUE / 1_000_000L
                || maxResponseBytes <= 0 || maxResponseBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("kb.ai.retrieval 配置无效");
        }
    }

    public static RetrievalProperties defaults() {
        return new RetrievalProperties(10000, 4194304);
    }
}
