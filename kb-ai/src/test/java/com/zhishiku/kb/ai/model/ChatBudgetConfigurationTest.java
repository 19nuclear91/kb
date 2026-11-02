package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.config.ConversationEnvironmentValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatBudgetConfigurationTest {
    private static final String PREFIX = "kb.ai.chat-model.budget.";

    static MockEnvironment environment() {
        return new MockEnvironment().withProperty("kb.ai.chat-model.enabled", "true")
                .withProperty("kb.ai.chat-model.base-url", "https://model.invalid/v1")
                .withProperty("kb.ai.chat-model.api-key", "secret-canary")
                .withProperty("kb.ai.chat-model.model", "text-model");
    }

    @Test
    void bindsDefaultsAndAllExplicitOverrides() {
        var defaults = ChatModelEnvironmentValidator.validate(environment()).getBudget();
        assertThat(defaults.getMode()).isEqualTo("verified");
        assertThat(defaults.getContextWindowTokens()).isZero();
        assertThat(defaults.getTokensPerKib()).isEqualTo(1024);
        assertThat(defaults.getPerMessageTokens()).isEqualTo(16);
        assertThat(defaults.getRequestOverheadTokens()).isEqualTo(64);
        assertThat(defaults.getSafetyMarginTokens()).isEqualTo(512);
        var env = environment().withProperty(PREFIX + "mode", "estimated")
                .withProperty(PREFIX + "context-window-tokens", "4096")
                .withProperty(PREFIX + "tokens-per-kib", "512")
                .withProperty(PREFIX + "per-message-tokens", "2")
                .withProperty(PREFIX + "request-overhead-tokens", "3")
                .withProperty(PREFIX + "safety-margin-tokens", "100");
        var budget = ChatModelEnvironmentValidator.validate(env).getBudget();
        assertThat(budget.getMode()).isEqualTo("estimated");
        assertThat(budget.getContextWindowTokens()).isEqualTo(4096);
        assertThat(budget.getTokensPerKib()).isEqualTo(512);
        assertThat(budget.getPerMessageTokens()).isEqualTo(2);
        assertThat(budget.getRequestOverheadTokens()).isEqualTo(3);
        assertThat(budget.getSafetyMarginTokens()).isEqualTo(100);
    }

    @Test
    void rejectsUnknownModeKeysAndNonIntegralOrOutOfRangeParameters() {
        Map<String, String> invalid = Map.ofEntries(
                Map.entry("mode", "ESTIMATED"), Map.entry("unknown", "secret-canary"),
                Map.entry("context-window-tokens", "2147483648"), Map.entry("tokens-per-kib", "1.5"),
                Map.entry("per-message-tokens", "NaN"), Map.entry("request-overhead-tokens", "Infinity"),
                Map.entry("safety-margin-tokens", "2147483648"));
        invalid.forEach((key, value) -> assertInvalid(environment().withProperty(PREFIX + key, value)));
        Map<String, String[]> boundaries = Map.of(
                "context-window-tokens", new String[]{"0", "-1"},
                "tokens-per-kib", new String[]{"0", "65537"},
                "per-message-tokens", new String[]{"-1", "65537"},
                "request-overhead-tokens", new String[]{"-1", "65537"},
                "safety-margin-tokens", new String[]{"-1", "2147483647"});
        boundaries.forEach((key, values) -> {
            for (String value : values) {
                assertInvalid(estimated().withProperty(PREFIX + key, value));
            }
        });
        assertInvalid(environment().withProperty(PREFIX + "mode", "estimated"));
    }

    @Test
    void capacityIsStrictAndUsesMultiTurnOutputAndTheSmallerWindow() {
        assertInvalid(estimated().withProperty(PREFIX + "context-window-tokens", "2560"));
        assertThatCode(() -> ChatModelEnvironmentValidator.validate(estimated()
                .withProperty(PREFIX + "context-window-tokens", "2561"))).doesNotThrowAnyException();
        assertInvalid(estimated().withProperty(PREFIX + "context-window-tokens", "2147483647")
                .withProperty("kb.conversation.max-output-tokens", "11488"));
        assertThatCode(() -> ChatModelEnvironmentValidator.validate(estimated()
                .withProperty("kb.conversation.max-output-tokens", "11487"))).doesNotThrowAnyException();
        assertInvalid(estimated().withProperty("kb.conversation.max-output-tokens", "2147483647")
                .withProperty(PREFIX + "safety-margin-tokens", "2147483647"));
        assertInvalid(estimated().withProperty(PREFIX + "context-window-tokens", "2560")
                .withProperty("kb.ai.chat-model.single-max-output-tokens", "1"));
        assertThatCode(() -> ChatModelEnvironmentValidator.validate(estimated()
                .withProperty(PREFIX + "context-window-tokens", "600")
                .withProperty("kb.conversation.max-output-tokens", "64"))).doesNotThrowAnyException();
    }

    @Test
    void parameterExtremesAndCheckedArithmeticDoNotWrap() {
        var env = estimated().withProperty(PREFIX + "context-window-tokens", "2147483647")
                .withProperty(PREFIX + "tokens-per-kib", "65536")
                .withProperty(PREFIX + "per-message-tokens", "65536")
                .withProperty(PREFIX + "request-overhead-tokens", "65536")
                .withProperty(PREFIX + "safety-margin-tokens", "0");
        assertThatCode(() -> ChatModelEnvironmentValidator.validate(env)).doesNotThrowAnyException();
        var budget = ChatModelEnvironmentValidator.validate(env).getBudget();
        budget.setSafetyMarginTokens(Integer.MAX_VALUE);
        assertThatThrownBy(() -> budget.validate(Long.MAX_VALUE, Long.MAX_VALUE))
                .hasMessage("聊天模型配置无效").hasNoCause();
        budget.setSafetyMarginTokens(0);
        budget.setTokensPerKib(1);
        budget.setPerMessageTokens(0);
        budget.setRequestOverheadTokens(0);
        assertThatCode(() -> budget.validate(12000, 2048)).doesNotThrowAnyException();
    }

    @Test
    void numericPropertySourcesCannotTruncateFloatingPointValues() {
        for (String key : new String[]{"context-window-tokens", "tokens-per-kib", "tokensPerKib", "per-message-tokens",
                "request-overhead-tokens", "safety-margin-tokens"}) {
            var environment = estimated();
            environment.getPropertySources().addFirst(new MapPropertySource("yaml-like", Map.of(PREFIX + key, 4096.5)));
            assertInvalid(environment);
        }
        var environment = estimated().withProperty(PREFIX + "tokens-per-kib", "${BUDGET_TEST_RATIO:1024}")
                .withProperty("BUDGET_TEST_RATIO", "512");
        assertThat(ChatModelEnvironmentValidator.validate(environment).getBudget().getTokensPerKib()).isEqualTo(512);
        environment.withProperty("BUDGET_TEST_RATIO", "512.5");
        assertInvalid(environment);
    }

    @Test
    void preservesReservationParityAndDisabledBehavior() {
        var env = estimated().withProperty("kb.conversation.enabled", "true")
                .withProperty("kb.conversation.secret", "s".repeat(32))
                .withProperty("kb.conversation.reservation-tokens", "13000");
        assertThatThrownBy(() -> ConversationEnvironmentValidator.validate(env))
                .hasMessage("多轮预留必须与 AI 单请求配额相同");
        assertThat(ChatModelEnvironmentValidator.validate(environment()
                .withProperty("kb.ai.chat-model.enabled", "false")
                .withProperty(PREFIX + "mode", "estimated")).isEnabled()).isFalse();
    }

    private static MockEnvironment estimated() {
        return environment().withProperty(PREFIX + "mode", "estimated")
                .withProperty(PREFIX + "context-window-tokens", "16000");
    }

    private static void assertInvalid(MockEnvironment environment) {
        assertThatThrownBy(() -> ChatModelEnvironmentValidator.validate(environment))
                .hasMessage("聊天模型配置无效").hasNoCause();
    }
}
