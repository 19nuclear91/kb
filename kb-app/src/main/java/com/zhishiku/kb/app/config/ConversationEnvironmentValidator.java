package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.conversation.ConversationSettings;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;

/**
 * 在创建业务连接前校验多轮配置。
 */
public final class ConversationEnvironmentValidator {
    private ConversationEnvironmentValidator() {
    }

    public static void validate(ConfigurableEnvironment environment) {
        var settings = Binder.get(environment).bind("kb.conversation", Bindable.of(ConversationSettings.class))
                .orElseGet(ConversationSettings::new);
        var profiles = environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles();
        settings.validate(Arrays.asList(profiles).contains("prod"));
        if (settings.isEnabled()) {
            try {
                URI endpoint = URI.create(environment.getProperty("kb.conversation.ai-base-url", ""));
                if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                        || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
                    throw new IllegalArgumentException();
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("多轮结算 AI 地址无效");
            }
        }
    }
}

