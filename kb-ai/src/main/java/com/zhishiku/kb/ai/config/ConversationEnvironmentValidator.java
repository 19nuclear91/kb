package com.zhishiku.kb.ai.config;

import com.zhishiku.kb.common.conversation.ConversationSettings;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;

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
        if (settings.isEnabled() && settings.getReservationTokens() != environment.getProperty("kb.ai.max-tokens-per" +
                "-request", Integer.class, 12000)) {
            throw new IllegalArgumentException("多轮预留必须与 AI 单请求配额相同");
        }
    }
}

