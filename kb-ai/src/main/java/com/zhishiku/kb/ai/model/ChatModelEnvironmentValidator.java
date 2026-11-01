package com.zhishiku.kb.ai.model;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;

/** 在建立任何业务连接前校验；绑定错误不携带含秘密的原始 cause。 */
public final class ChatModelEnvironmentValidator {
    private ChatModelEnvironmentValidator() {
    }

    public static ChatModelProperties validate(ConfigurableEnvironment environment) {
        try {
            var properties = Binder.get(environment).bind("kb.ai.chat-model", Bindable.of(ChatModelProperties.class),
                    new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                    .orElseGet(ChatModelProperties::new);
            var profiles = Arrays.asList(environment.getActiveProfiles().length == 0
                    ? environment.getDefaultProfiles() : environment.getActiveProfiles());
            properties.validate(!profiles.contains("dev") || profiles.contains("prod"),
                    environment.getProperty("kb.ai.upstream-timeout", Integer.class, 60),
                    environment.getProperty("kb.ai.max-tokens-per-request", Integer.class, 12000));
            return properties;
        } catch (RuntimeException error) {
            throw ChatModelProperties.invalid();
        }
    }
}
