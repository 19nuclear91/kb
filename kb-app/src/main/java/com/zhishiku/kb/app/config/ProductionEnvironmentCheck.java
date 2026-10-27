package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.search.embedding.EmbeddingEnvironmentValidator;
import com.zhishiku.kb.common.config.ProductionSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * 配置加载后、连接中间件和执行 Flyway 之前校验生产边界。
 */
public class ProductionEnvironmentCheck implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ConversationEnvironmentValidator.validate(environment);
        EmbeddingEnvironmentValidator.validate(environment);
        var profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles()));
        var settings = new HashMap<String, String>();
        for (var source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String key : enumerable.getPropertyNames()) {
                    if (key.startsWith("LOGGING_LEVEL_")) {
                        String loggerKey = "logging.level." + key.substring("LOGGING_LEVEL_".length())
                                .toLowerCase(Locale.ROOT).replace('_', '.');
                        settings.put(loggerKey, environment.getProperty(key));
                    }
                    if (key.startsWith("spring.") || key.startsWith("kb.")
                            || key.startsWith("logging.level.") || key.startsWith("sa-token.")) {
                        settings.put(key, environment.getProperty(key));
                    }
                }
            }
        }
        // 显式查询标准键，使环境变量、命令行等高优先级覆盖参与同一校验。
        for (String key : new String[]{"spring.datasource.url", "spring.datasource.username",
                "spring.datasource.password", "spring.data.redis.host", "spring.flyway.locations",
                "spring.flyway.baseline-on-migrate", "kb.dev-auth.enabled", "kb.storage.endpoint",
                "kb.storage.access-key", "kb.storage.secret-key", "kb.storage.bucket",
                "kb.ai.app-base-url", "kb.ai.audit-in-memory-enabled", "sa-token.is-log", "logging.level.root",
                "spring.cloud.gateway.routes[0].uri", "spring.cloud.gateway.routes[1].uri"}) {
            settings.put(key, environment.getProperty(key));
        }
        ProductionSettings.validate("kb-app", profiles, settings);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
