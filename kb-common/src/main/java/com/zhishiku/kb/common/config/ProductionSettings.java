package com.zhishiku.kb.common.config;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * 在创建数据库连接和执行迁移前检查有效配置；错误只包含配置名，不包含密钥。
 */
public final class ProductionSettings {
    private ProductionSettings() {
    }

    public static void validate(String module, Set<String> profiles, Map<String, String> settings) {
        if (profiles.contains("dev")) {
            if (profiles.contains("prod")) fail("dev 与 prod 不得同时启用");
            return;
        }
        if (module.equals("kb-app") || module.equals("kb-worker")) {
            require(settings, "spring.datasource.url", "spring.datasource.username",
                    "spring.datasource.password", "kb.storage.endpoint",
                    "kb.storage.access-key", "kb.storage.secret-key", "kb.storage.bucket");
            rejectExample(settings, "spring.datasource.password", "kb_dev_pwd");
            rejectExample(settings, "kb.storage.secret-key", "kbminio_dev_pwd");
        }
        if (!module.equals("kb-worker")) require(settings, "spring.data.redis.host");
        if (module.equals("kb-app")) {
            if (Boolean.parseBoolean(settings.get("kb.dev-auth.enabled"))) fail("生产禁止 kb.dev-auth.enabled");
            if (!"classpath:db/migration".equals(settings.get("spring.flyway.locations")))
                fail("生产 spring.flyway.locations 只能包含 classpath:db/migration");
            if (Boolean.parseBoolean(settings.get("spring.flyway.baseline-on-migrate")))
                fail("生产禁止自动基线化未知数据库");
        }
        if (module.equals("kb-ai")) {
            require(settings, "kb.ai.app-base-url");
            if (Boolean.parseBoolean(settings.get("kb.ai.audit-in-memory-enabled")))
                fail("生产禁止内存 AI 审计");
        }
        if (module.equals("kb-gateway")) {
            require(settings, "spring.cloud.gateway.routes[0].uri", "spring.cloud.gateway.routes[1].uri");
            boolean originsPresent = false;
            for (var entry : settings.entrySet()) {
                String key = entry.getKey().toLowerCase().replace("-", "");
                if (!key.contains("globalcors") || !key.contains("allowedorigin")) continue;
                String value = entry.getValue();
                if (value == null || value.isBlank()) continue;
                if (key.contains("patterns")) fail("生产 CORS 必须使用明确 allowedOrigins");
                originsPresent = true;
                for (String origin : value.split(",")) {
                    try {
                        URI uri = URI.create(origin.trim());
                        if (origin.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                                || !Set.of("http", "https").contains(uri.getScheme())
                                || (uri.getPath() != null && !uri.getPath().isEmpty())
                                || uri.getQuery() != null || uri.getFragment() != null)
                            fail("生产 CORS origin 必须是完整且无通配的来源");
                    } catch (IllegalArgumentException e) {
                        fail("生产 CORS origin 配置无效");
                    }
                }
            }
            if (!originsPresent) fail("生产必须配置 CORS allowedOrigins");
        }
        for (var entry : settings.entrySet()) {
            if (entry.getKey().startsWith("logging.level.")
                    && ("DEBUG".equalsIgnoreCase(entry.getValue()) || "TRACE".equalsIgnoreCase(entry.getValue())))
                fail("生产禁止详细日志：" + entry.getKey());
            if ("sa-token.is-log".equals(entry.getKey()) && Boolean.parseBoolean(entry.getValue()))
                fail("生产禁止 Sa-Token 调试日志");
        }
    }

    private static void require(Map<String, String> settings, String... keys) {
        for (String key : keys) {
            String value = settings.get(key);
            if (value == null || value.isBlank() || value.contains("${")) fail("生产缺少配置：" + key);
        }
    }

    private static void rejectExample(Map<String, String> settings, String key, String example) {
        if (example.equals(settings.get(key))) fail("生产不能使用示例凭据：" + key);
    }

    private static void fail(String message) {
        throw new IllegalStateException(message);
    }
}

