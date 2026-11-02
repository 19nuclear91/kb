package com.zhishiku.kb.ai.telemetry;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.Set;

/** Boot 3.3 的 tracing 开关不控制本地跨度创建，须在自动配置导入前隔离。 */
public final class TracingAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {
    private static final Set<String> CONFIGURATIONS = Set.of(
            "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.prometheus.PrometheusExemplarsAutoConfiguration");
    private boolean enabled;

    @Override
    public void setEnvironment(Environment environment) {
        enabled = environment.getProperty("kb.telemetry.tracing.enabled", Boolean.class, false);
    }

    @Override
    public boolean[] match(String[] names, AutoConfigurationMetadata metadata) {
        boolean[] matches = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            matches[i] = enabled || names[i] == null || !CONFIGURATIONS.contains(names[i]);
        }
        return matches;
    }
}
