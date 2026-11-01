package com.zhishiku.kb.ai.model;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelPropertiesTest {
    static ChatModelProperties configured() {
        var properties = new ChatModelProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://model.invalid/v1/");
        properties.setApiKey("secret-canary");
        properties.setModel("text-model");
        return properties;
    }

    @Test
    void disabledDefaultsRequireNoCredentials() {
        var properties = new ChatModelProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isRequestUsage()).isTrue();
        assertThat(properties.getOutputTokenField()).isEqualTo("max_tokens");
        assertThat(properties.getSingleMaxOutputTokens()).isEqualTo(2048);
        assertThat(properties.getMaxRequestBytes()).isEqualTo(1048576);
        assertThat(properties.getMaxEventBytes()).isEqualTo(1048576);
        assertThat(properties.getMaxResponseBytes()).isEqualTo(4194304);
        assertThat(properties.getMaxAnswerCodepoints()).isEqualTo(64000);
        assertThatCode(() -> properties.validate(true, 0, 0)).doesNotThrowAnyException();
        assertThatCode(() -> configured().validate(true, 60, 12000)).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidFieldsWithoutPrintingSecrets() {
        List<Consumer<ChatModelProperties>> changes = List.of(
                p -> p.setApiKey(null), p -> p.setModel(" "), p -> p.setApiKey("secret\ncanary"),
                p -> p.setModel("text\tmodel"), p -> p.setOutputTokenField("automatic"),
                p -> p.setSingleMaxOutputTokens(0), p -> p.setSingleMaxOutputTokens(12001),
                p -> p.setConnectTimeoutMs(0), p -> p.setConnectTimeoutMs(60001),
                p -> p.setIdleTimeoutMs(-1), p -> p.setIdleTimeoutMs(60001),
                p -> p.setMaxRequestBytes(0), p -> p.setMaxEventBytes(0),
                p -> p.setMaxResponseBytes(1), p -> p.setMaxAnswerCodepoints(0));
        for (var change : changes) {
            var properties = configured();
            change.accept(properties);
            assertThatThrownBy(() -> properties.validate(true, 60, 12000))
                    .hasMessage("聊天模型配置无效").hasNoCause();
            assertThat(properties.toString()).doesNotContain("secret", "text-model");
        }
        assertThatThrownBy(() -> configured().validate(true, 0, 12000)).hasMessage("聊天模型配置无效");
        assertThatThrownBy(() -> configured().validate(true, 60, 1)).hasMessage("聊天模型配置无效");
    }

    @Test
    void supportsOnlyExplicitAlternativeOutputField() {
        var properties = configured();
        properties.setOutputTokenField("max_completion_tokens");
        properties.setRequestUsage(false);
        assertThatCode(() -> properties.validate(true, 60, 12000)).doesNotThrowAnyException();
    }

    @Test
    void bindsAndValidatesWithoutApplicationContext() {
        var environment = new MockEnvironment();
        assertThat(ChatModelEnvironmentValidator.validate(environment).isEnabled()).isFalse();
        environment.setActiveProfiles("dev");
        environment.withProperty("kb.ai.chat-model.enabled", "true")
                .withProperty("kb.ai.chat-model.base-url", "http://model.invalid/v1/")
                .withProperty("kb.ai.chat-model.api-key", "secret-canary")
                .withProperty("kb.ai.chat-model.model", "test-model")
                .withProperty("kb.ai.chat-model.request-usage", "false");
        assertThat(ChatModelEnvironmentValidator.validate(environment).isRequestUsage()).isFalse();
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> ChatModelEnvironmentValidator.validate(environment))
                .hasMessage("聊天模型配置无效").hasNoCause();
        environment.setActiveProfiles("dev");
        environment.withProperty("kb.ai.chat-model.max-event-bytes", "secret-invalid");
        assertThatThrownBy(() -> ChatModelEnvironmentValidator.validate(environment))
                .hasMessage("聊天模型配置无效").hasNoCause();
    }

    @Test
    void rejectsUnknownConfigurationWithoutLeakingItsValue() {
        var environment = new MockEnvironment().withProperty("kb.ai.chat-model.unknown", "secret-canary");
        assertThatThrownBy(() -> ChatModelEnvironmentValidator.validate(environment))
                .hasMessage("聊天模型配置无效").hasNoCause();
    }

    @Test
    void preservesPrefixAndRejectsUnsafeRoots() {
        assertThat(configured().endpoint().toString()).isEqualTo("https://model.invalid/v1/chat/completions");
        for (String value : List.of("https://user:secret@model.invalid/v1", "https://model.invalid/v1?secret=x",
                "https://model.invalid/v1#secret", "https://model.invalid/v1/chat/completions",
                "https://model.invalid/a/../v1", "file:///secret", "secret\nhttps://model.invalid")) {
            var properties = configured();
            properties.setBaseUrl(value);
            assertThatThrownBy(() -> properties.validate(true, 60, 12000))
                    .hasMessage("聊天模型配置无效").hasNoCause();
        }
        var properties = configured();
        properties.setBaseUrl("http://model.invalid/v1");
        assertThatThrownBy(() -> properties.validate(true, 60, 12000)).hasMessage("聊天模型配置无效");
        assertThatCode(() -> properties.validate(false, 60, 12000)).doesNotThrowAnyException();
    }
}
