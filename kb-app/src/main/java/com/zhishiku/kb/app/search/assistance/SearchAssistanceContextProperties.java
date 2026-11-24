package com.zhishiku.kb.app.search.assistance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 建议上下文与管理游标的用途隔离密钥配置；非法或缺失值不在构造期终止原词服务。
 * @param keyId 当前签发密钥标识
 * @param secret 当前独立随机秘密，至少 32 UTF-8 字节
 * @param previousKeyId 可选前任验证密钥标识
 * @param previousSecret 可选前任验证秘密
 */
@ConfigurationProperties(prefix = "kb.search.assistance.context")
public record SearchAssistanceContextProperties(String keyId, @JsonIgnore String secret,
                                                String previousKeyId, @JsonIgnore String previousSecret) {
    @Override
    public String toString() {
        return "SearchAssistanceContextProperties[redacted]";
    }
}
