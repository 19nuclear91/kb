package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 部署允许使用的嵌入配置清单。
 *
 * @param profiles 部署显式声明的不可变嵌入配置清单
 */
@ConfigurationProperties(prefix = "kb.embedding")
public record EmbeddingProfilesProperties(List<EmbeddingProfileDefinition> profiles) {
    public EmbeddingProfilesProperties {
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException("kb.embedding.profiles must be explicit and non-empty");
        }
        profiles = List.copyOf(profiles);
    }

    @Override
    public String toString() {
        return "EmbeddingProfilesProperties[count=" + profiles.size() + "]";
    }
}
