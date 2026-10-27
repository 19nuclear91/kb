package com.zhishiku.kb.worker.embedding;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.HashSet;

public final class EmbeddingEnvironmentValidator {
    private EmbeddingEnvironmentValidator() {
    }

    public static void validate(ConfigurableEnvironment environment) {
        for (String prefix : new String[]{"kb.embedding.", "kb.search.embedding."}) {
            for (String field : new String[]{"provider", "base-url", "api-key", "model", "dimension",
                    "batch-size", "timeout-seconds"}) {
                if (environment.containsProperty(prefix + field)) {
                    throw new IllegalStateException("migrate " + prefix + field + " to kb.embedding.profiles");
                }
            }
        }
        boolean dev = Arrays.asList(environment.getActiveProfiles()).contains("dev");
        if (dev && Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("embedding dev and prod profiles must not be combined");
        }
        try {
            var properties = Binder.get(environment).bind("kb.embedding",
                            Bindable.of(EmbeddingProfilesProperties.class))
                    .orElseThrow(() -> new IllegalArgumentException("missing profiles"));
            var ids = new HashSet<String>();
            for (var definition : properties.profiles()) {
                if (!ids.add(definition.profile().profileId())) {
                    throw new IllegalArgumentException("duplicate profiles");
                }
                if (!dev && ("local".equals(definition.profile().provider())
                        || "local-hash-v1".equals(definition.profile().model()))) {
                    throw new IllegalArgumentException("production local embedding");
                }
            }
        } catch (RuntimeException e) {
            // 绑定异常可能携带原始配置与密钥，不向启动错误链传播。
            throw new IllegalStateException("invalid kb.embedding.profiles; check semantic fields, provider, " +
                    "transforms, connection and limits");
        }
    }
}
