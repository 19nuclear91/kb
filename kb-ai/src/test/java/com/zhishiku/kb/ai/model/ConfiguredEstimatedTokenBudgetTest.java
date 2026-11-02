package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.conversation.ConversationBudget;
import com.zhishiku.kb.ai.conversation.MultiTurnModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredEstimatedTokenBudgetTest {
    static ChatModelProperties.Budget settings() {
        var settings = new ChatModelProperties.Budget();
        settings.setMode("estimated");
        settings.setContextWindowTokens(16000);
        settings.setTokensPerKib(512);
        settings.setPerMessageTokens(2);
        settings.setRequestOverheadTokens(3);
        return settings;
    }

    static ConfiguredEstimatedTokenBudget estimator(ChatModelProperties.Budget settings) {
        return new ConfiguredEstimatedTokenBudget(ChatModelIdentity.of(ChatModelPropertiesTest.configured()), settings);
    }

    static PreparedChatRequest request(PreparedChatRequest.Message... messages) {
        return new PreparedChatRequest("text-model", List.of(messages), 2048, "max_tokens", true, 1048576);
    }

    @Test
    void handCalculatedMixedLanguagesAndAggregateRounding() {
        var budget = estimator(settings());
        assertThat(budget.count(request(new PreparedChatRequest.Message("user", "A"),
                new PreparedChatRequest.Message("assistant", "中😀"))))
                .isEqualTo(new InputTokenCount(BudgetKind.ESTIMATED, 18));
        assertThat(budget.count(request(new PreparedChatRequest.Message("user", "A"),
                new PreparedChatRequest.Message("user", "A"))).tokens()).isEqualTo(12);
        // system:é 是 8 字节，user:A 为 5，assistant:中😀 为 16，共 29；15 + 6 + 3。
        assertThat(budget.count(request(new PreparedChatRequest.Message("system", "é"),
                new PreparedChatRequest.Message("user", "A"),
                new PreparedChatRequest.Message("assistant", "中😀"))).tokens()).isEqualTo(24);
    }

    @Test
    void freezesIdentityAndSettingsWithoutMutatingMessagesOrLeakingContent() {
        var settings = settings();
        var budget = estimator(settings);
        var request = request(new PreparedChatRequest.Message("user", "secret-canary😀"));
        var encoded = request.encode();
        var result = budget.count(request);
        settings.setTokensPerKib(65536);
        settings.setContextWindowTokens(1);
        settings.setSafetyMarginTokens(0);
        assertThat(budget.count(request)).isEqualTo(result);
        assertThat(budget.capability()).isEqualTo(new BudgetCapability(BudgetKind.ESTIMATED, 16000, 512));
        assertThat(request.encode()).containsExactly(encoded);
        assertThat(budget.toString()).doesNotContain("secret", "model.invalid", "text-model");
        assertThatThrownBy(() -> budget.count(new PreparedChatRequest("other", request.messages(), 2048,
                "max_tokens", false, 1048576))).hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
        var wrongFormat = new ChatModelIdentity(ChatModelPropertiesTest.configured().rootUri(), "text-model", "v2");
        assertThatThrownBy(() -> new ConfiguredEstimatedTokenBudget(wrongFormat, settings()).count(request))
                .hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
    }

    @Test
    void rejectsMalformedUnicodeAndNullWithoutRawCauses() {
        for (String value : new String[]{"secret\u0000", "\uD800", "\uDC00", "\uD800x", null}) {
            var budget = estimator(settings());
            assertThatThrownBy(() -> budget.count(request(new PreparedChatRequest.Message("user", value))))
                    .hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
            assertThatThrownBy(() -> budget.count(request(new PreparedChatRequest.Message(value, "text"))))
                    .hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
        }
        assertThat(ChatJson.utf8Length("Aé中😀\n")).isEqualTo(11);
    }

    @Test
    void maximumParametersAndAllArithmeticOverflowPathsFailClosed() {
        var settings = settings();
        settings.setContextWindowTokens(Integer.MAX_VALUE);
        settings.setSafetyMarginTokens(Integer.MAX_VALUE);
        settings.setTokensPerKib(65536);
        settings.setPerMessageTokens(65536);
        settings.setRequestOverheadTokens(65536);
        var budget = estimator(settings);
        assertThat(budget.estimate(21, 2)).isEqualTo(197952);
        assertThatThrownBy(() -> budget.estimate(Long.MAX_VALUE, 1)).hasMessage("BUDGET_UNAVAILABLE");
        assertThatThrownBy(() -> budget.estimate(0, Long.MAX_VALUE)).hasMessage("BUDGET_UNAVAILABLE");
        assertThatThrownBy(() -> budget.estimate(-1, 0)).hasMessage("BUDGET_UNAVAILABLE");
        assertThatThrownBy(() -> budget.estimate(0, -1)).hasMessage("BUDGET_UNAVAILABLE");
        settings.setTokensPerKib(1);
        settings.setPerMessageTokens(1);
        settings.setRequestOverheadTokens(0);
        var minimal = estimator(settings);
        assertThat(minimal.estimate(Long.MAX_VALUE, 0)).isEqualTo(9007199254740992L);
        assertThatThrownBy(() -> minimal.estimate(1, Long.MAX_VALUE)).hasMessage("BUDGET_UNAVAILABLE");
        settings.setRequestOverheadTokens(1);
        assertThatThrownBy(() -> estimator(settings).estimate(0, Long.MAX_VALUE))
                .hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
    }

    @Test
    void includesMaterialJsonAndEscapesButNotHttpEnvelopeOrCredentials() {
        var properties = ChatModelPropertiesTest.configured();
        var settings = settings();
        settings.setTokensPerKib(1024);
        settings.setPerMessageTokens(0);
        settings.setRequestOverheadTokens(0);
        var input = new MultiTurnModel.Input(ConversationBudget.INSTRUCTION,
                List.of(new MultiTurnModel.Message("user", "Q")),
                List.of(new RetrievalClient.Citation(1, "title", 2, 3, "A\n\"\\", 4L,
                        null, null, null, null, null, null, null, null, null, false)), 2048);
        var prepared = new ChatPromptRenderer(properties).render(input);
        // 指令 120 字节，材料说明 91，JSON 70，角色 14，问题 1，总计 296。
        assertThat(estimator(settings).count(prepared).tokens()).isEqualTo(296);
        var alternateEnvelope = new PreparedChatRequest("text-model", prepared.messages(), 1,
                "max_completion_tokens", false, 1048576);
        assertThat(estimator(settings).count(alternateEnvelope)).isEqualTo(estimator(settings).count(prepared));
        assertThat(prepared.encode().length).isGreaterThan(296);
        properties.setApiKey("different-secret");
        assertThat(estimator(settings).count(new ChatPromptRenderer(properties).render(input)))
                .isEqualTo(estimator(settings).count(prepared));
    }
}
