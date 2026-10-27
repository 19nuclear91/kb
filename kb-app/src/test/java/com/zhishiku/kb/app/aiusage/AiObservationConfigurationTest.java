package com.zhishiku.kb.app.aiusage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.*;

/**
 * 仅装配配置值，无自动配置、服务或连接。
 */
class AiObservationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Test
    void bindsIndependentPropertiesWhenConversationIsDisabled() {
        context.withPropertyValues("kb.conversation.enabled=false", "kb.ai.observability.enabled=true",
                "kb.ai.observability.secret=0123456789abcdef0123456789abcdef").run(result -> {
            assertThat(result).hasNotFailed(); var properties = result.getBean(AiObservationProperties.class);
            assertThat(properties.isEnabled()).isTrue(); assertThat(properties.getRetentionDays()).isEqualTo(180);
        });
    }
    @Test
    void malformedSettingsFailDuringBeanInitialization() {
        context.withPropertyValues("kb.ai.observability.enabled=true", "kb.ai.observability.secret=short").run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("kb.ai.observability.retention-days=0").run(result -> assertThat(result).hasFailed());
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiObservationProperties.class)
    static class Config { }
}
