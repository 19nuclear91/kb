package com.zhishiku.kb.worker.embedding.execution;

import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.env.ConfigurableEnvironment;

/** Worker 运行限额，不参与模型语义身份。 */
public record WorkerEmbeddingSettings(
        @DefaultValue("1048576") int maxRequestBytes,
        @DefaultValue("4194304") int maxResponseBytes,
        @DefaultValue("64") int maxJsonDepth,
        @DefaultValue("300") int rebuildGenerationTimeoutSeconds,
        @DefaultValue("10") int probeTimeoutSeconds) {

    public WorkerEmbeddingSettings {
        requireRange(maxRequestBytes, 16777216);
        requireRange(maxResponseBytes, 67108864);
        requireRange(maxJsonDepth, 128);
        requireRange(rebuildGenerationTimeoutSeconds, 3600);
        requireRange(probeTimeoutSeconds, 60);
    }

    public static WorkerEmbeddingSettings defaults() {
        return new WorkerEmbeddingSettings(1048576, 4194304, 64, 300, 10);
    }

    public static WorkerEmbeddingSettings from(ConfigurableEnvironment environment) {
        try {
            var handler = new AbstractBindHandler(new NoUnboundElementsBindHandler(BindHandler.DEFAULT)) {
                @Override
                public Object onSuccess(ConfigurationPropertyName name, Bindable<?> target,
                                        BindContext context, Object result) {
                    if (result instanceof Integer && context.getConfigurationProperty() != null) {
                        String raw = environment.resolveRequiredPlaceholders(
                                String.valueOf(context.getConfigurationProperty().getValue()));
                        if (!raw.matches("[0-9]+")) {
                            throw invalid();
                        }
                    }
                    return super.onSuccess(name, target, context, result);
                }
            };
            return Binder.get(environment)
                    .bind("kb.worker.embedding", Bindable.of(WorkerEmbeddingSettings.class), handler)
                    .orElseGet(WorkerEmbeddingSettings::defaults);
        } catch (RuntimeException error) {
            // 原绑定异常可能包含配置值，不保留 cause。
            throw invalid();
        }
    }

    private static void requireRange(int value, int maximum) {
        if (value < 1 || value > maximum) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid kb.worker.embedding limits");
    }
}
