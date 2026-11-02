package com.zhishiku.kb.worker.telemetry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 在自动配置与业务连接建立前绑定并校验，原始绑定异常不得携带配置值。 */
public final class TelemetryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String PREFIX = "kb.telemetry.tracing.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        HistogramConfiguration.buckets(environment);
        Binder binder = Binder.get(environment);
        boolean enabled = value(binder, PREFIX + "enabled", Boolean.class, false);
        boolean nativeEnabled = value(binder, "management.tracing.enabled", Boolean.class, enabled);
        if (nativeEnabled != enabled) {
            throw invalid("management.tracing.enabled");
        }
        String endpoint = value(binder, PREFIX + "otlp-endpoint", String.class,
                value(binder, "management.otlp.tracing.endpoint", String.class, ""));
        double probability = value(binder, PREFIX + "sampling-probability", Double.class,
                value(binder, "management.tracing.sampling.probability", Double.class, 1.0));
        if (enabled) {
            validateEndpoint(endpoint);
            if (!Double.isFinite(probability) || probability <= 0 || probability > 1) {
                throw invalid(PREFIX + "sampling-probability");
            }
        }
        // 原生键也必须校验，不能以映射覆盖来掩盖非法配置。
        String nativeEndpoint = value(binder, "management.otlp.tracing.endpoint", String.class, "");
        if (enabled && !nativeEndpoint.isBlank()) {
            validateEndpoint(nativeEndpoint);
        }
        double nativeProbability = value(binder, "management.tracing.sampling.probability", Double.class, probability);
        if (enabled && (!Double.isFinite(nativeProbability) || nativeProbability <= 0 || nativeProbability > 1)) {
            throw invalid("management.tracing.sampling.probability");
        }
        if (value(binder, "management.tracing.baggage.enabled", Boolean.class, false)) {
            throw invalid("management.tracing.baggage.enabled");
        }
        try {
            List<String> fields = binder.bind("management.tracing.baggage.remote-fields", Bindable.listOf(String.class))
                    .orElse(List.of());
            if (!fields.isEmpty()) {
                throw invalid("management.tracing.baggage.remote-fields");
            }
        } catch (RuntimeException error) {
            throw invalid("management.tracing.baggage.remote-fields");
        }
        Map<String, Object> mapped = new HashMap<>();
        mapped.put("management.tracing.enabled", enabled);
        mapped.put("management.tracing.sampling.probability", probability);
        mapped.put("management.tracing.baggage.enabled", false);
        mapped.put("management.tracing.baggage.remote-fields", "");
        if (enabled) {
            mapped.put("management.otlp.tracing.endpoint", endpoint);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("kbTelemetry", mapped));
    }

    private static void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw invalid(PREFIX + "otlp-endpoint");
            }
        } catch (RuntimeException error) {
            throw invalid(PREFIX + "otlp-endpoint");
        }
    }

    private static <T> T value(Binder binder, String key, Class<T> type, T fallback) {
        try {
            return binder.bind(key, type).orElse(fallback);
        } catch (RuntimeException error) {
            throw invalid(key);
        }
    }

    private static IllegalStateException invalid(String key) {
        return new IllegalStateException("配置无效：" + key);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
