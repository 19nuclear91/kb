package com.zhishiku.kb.worker.config;

import com.zhishiku.kb.common.config.ProductionSettings;
import com.zhishiku.kb.common.config.ProductionSettingsCollector;
import com.zhishiku.kb.worker.embedding.EmbeddingEnvironmentValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

import java.util.Arrays;
import java.util.HashSet;

/**
 * 配置加载后、连接中间件和执行 Flyway 之前执行模块配置与生产边界检查。
 */
public class StartupEnvironmentCheck implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        EmbeddingEnvironmentValidator.validate(environment);
        var profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles()));
        var propertyNames = new HashSet<String>();
        for (var source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                propertyNames.addAll(Arrays.asList(enumerable.getPropertyNames()));
            }
        }
        var settings = ProductionSettingsCollector.collect(propertyNames, environment::getProperty);
        ProductionSettings.validate("kb-worker", profiles, settings);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
