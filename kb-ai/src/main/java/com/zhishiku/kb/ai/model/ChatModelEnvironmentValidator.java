package com.zhishiku.kb.ai.model;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;

/** 在建立任何业务连接前校验；绑定错误不携带含秘密的原始 cause。 */
public final class ChatModelEnvironmentValidator {
    private ChatModelEnvironmentValidator() {
    }

    public static ChatModelProperties validate(ConfigurableEnvironment environment) {
        try {
            var handler = new AbstractBindHandler(new NoUnboundElementsBindHandler(BindHandler.DEFAULT)) {
                @Override
                public Object onSuccess(ConfigurationPropertyName name, Bindable<?> target, BindContext context,
                                        Object result) {
                    // 在绑定层核对原始值，同时覆盖宽松名称与占位符，禁止浮点截断。
                    if (name.toString().startsWith("kb.ai.chat-model.budget.") && result instanceof Integer
                            && context.getConfigurationProperty() != null) {
                        String value = environment.resolveRequiredPlaceholders(
                                String.valueOf(context.getConfigurationProperty().getValue()));
                        if (!value.matches("-?[0-9]+")) {
                            throw ChatModelProperties.invalid();
                        }
                    }
                    return super.onSuccess(name, target, context, result);
                }
            };
            var properties = Binder.get(environment).bind("kb.ai.chat-model", Bindable.of(ChatModelProperties.class), handler)
                    .orElseGet(ChatModelProperties::new);
            var profiles = Arrays.asList(environment.getActiveProfiles().length == 0
                    ? environment.getDefaultProfiles() : environment.getActiveProfiles());
            properties.validate(!profiles.contains("dev") || profiles.contains("prod"),
                    environment.getProperty("kb.ai.upstream-timeout", Integer.class, 60),
                    environment.getProperty("kb.ai.max-tokens-per-request", Integer.class, 12000),
                    environment.getProperty("kb.conversation.max-output-tokens", Integer.class, 2048));
            return properties;
        } catch (RuntimeException error) {
            throw ChatModelProperties.invalid();
        }
    }
}
