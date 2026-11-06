package com.zhishiku.kb.common.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 收集启动检查所需的最终配置；由调用方提供属性解析器，公共模块不依赖 Spring。
 */
public final class ProductionSettingsCollector {
    private static final List<String> STANDARD_KEYS = List.of(
            "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
            "spring.data.redis.host", "spring.flyway.locations", "spring.flyway.baseline-on-migrate",
            "kb.dev-auth.enabled", "kb.storage.endpoint", "kb.storage.access-key", "kb.storage.secret-key",
            "kb.storage.bucket", "kb.ai.app-base-url", "kb.ai.audit-in-memory-enabled", "sa-token.is-log",
            "logging.level.root", "logging.level.sql", "spring.cloud.gateway.routes[0].uri", "spring.cloud.gateway.routes[1].uri",
            "server.port", "management.server.port", "management.server.address",
            "management.endpoints.web.exposure.include");

    private ProductionSettingsCollector() {
    }

    public static Map<String, String> collect(Iterable<String> propertyNames, Function<String, String> resolver) {
        var keys = new HashSet<>(STANDARD_KEYS);
        for (String key : propertyNames) {
            if (key.startsWith("LOGGING_LEVEL_") || key.startsWith("LOGGING_GROUP_")) {
                keys.add(key.toLowerCase(Locale.ROOT).replace('_', '.'));
            } else if (key.startsWith("spring.") || key.startsWith("kb.") || key.startsWith("management.")
                    || key.startsWith("logging.level.") || key.startsWith("logging.group.") || key.startsWith("sa-token.")) {
                keys.add(key);
            }
        }
        var settings = new HashMap<String, String>();
        for (String key : keys) {
            // 统一通过标准键取最终值，避免低优先级环境变量覆盖命令行配置。
            settings.put(key, resolver.apply(key));
        }
        return settings;
    }
}
