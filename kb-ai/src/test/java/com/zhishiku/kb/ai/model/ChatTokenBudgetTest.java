package com.zhishiku.kb.ai.model;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatTokenBudgetTest {
    private static final ChatModelIdentity ID = new ChatModelIdentity(
            URI.create("https://model.invalid/v1"), "text-model", ChatPromptRenderer.FORMAT_VERSION);

    static PreparedChatRequest prepared() {
        return new PreparedChatRequest("text-model", List.of(
                new PreparedChatRequest.Message("system", "指令"),
                new PreparedChatRequest.Message("user", "旧问题"),
                new PreparedChatRequest.Message("assistant", "旧答案"),
                new PreparedChatRequest.Message("user", "材料包装：[材料]"),
                new PreparedChatRequest.Message("user", "原问题😀")), 2048, "max_tokens", true, 1048576);
    }

    @Test
    void extensionReceivesTheExactPreparedMessagesIncludingWrapping() {
        var request = prepared();
        var original = request.encode();
        // 此确定性替身只验证接口传递，不是生产 token 估算。
        ChatTokenBudget policy = new ChatTokenBudget() {
            @Override
            public ChatModelIdentity identity() {
                return ID;
            }

            @Override
            public long contextWindowTokens() {
                return 16000;
            }

            @Override
            public long inputTokenUpperBound(PreparedChatRequest actual) {
                assertThat(actual).isSameAs(request);
                assertThat(actual.messages()).containsExactlyElementsOf(request.messages());
                return actual.messages().stream().mapToLong(message ->
                        message.role().length() + message.content().length() + 10).sum();
            }
        };
        var binding = new ChatBudgetBinding(ID, List.of(policy));
        assertThat(binding.available()).isTrue();
        assertThat(binding.window()).isEqualTo(16000);
        long expected = request.messages().stream()
                .mapToLong(message -> message.role().length() + message.content().length() + 10).sum();
        assertThat(binding.upperBound(request)).isEqualTo(expected);
        assertThat(binding.upperBound(request)).isEqualTo(expected);
        assertThat(request.encode()).containsExactly(original);
    }

    @Test
    void mismatchedAddressModelOrFormatNeverSelectsAnotherPolicy() {
        var policy = mock(ChatTokenBudget.class);
        when(policy.identity()).thenReturn(ID);
        when(policy.contextWindowTokens()).thenReturn(16000L);
        for (var changed : List.of(
                new ChatModelIdentity(URI.create("https://other.invalid/v1"), ID.model(), ID.formatVersion()),
                new ChatModelIdentity(ID.baseUrl(), "other-model", ID.formatVersion()),
                new ChatModelIdentity(ID.baseUrl(), ID.model(), "v2"))) {
            var binding = new ChatBudgetBinding(changed, List.of(policy));
            assertThat(binding.available()).isFalse();
            assertThat(binding.window()).isZero();
            assertThatThrownBy(() -> binding.upperBound(prepared()))
                    .hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
        }
        assertThat(new ChatBudgetBinding(ID, List.of()).available()).isFalse();
        assertThat(ID.toString()).doesNotContain("model.invalid", "text-model");
    }

    @Test
    void duplicateAndInvalidPoliciesFailWithoutLeakingTheirExceptions() {
        var policy = mock(ChatTokenBudget.class);
        when(policy.identity()).thenReturn(ID);
        when(policy.contextWindowTokens()).thenReturn(16000L);
        assertThatThrownBy(() -> new ChatBudgetBinding(ID, List.of(policy, policy)))
                .hasMessage("聊天模型配置无效").hasNoCause();
        when(policy.contextWindowTokens()).thenReturn(0L);
        assertThatThrownBy(() -> new ChatBudgetBinding(ID, List.of(policy))).hasMessage("聊天模型配置无效");
        when(policy.contextWindowTokens()).thenThrow(new IllegalStateException("secret-model"));
        assertThatThrownBy(() -> new ChatBudgetBinding(ID, List.of(policy)))
                .hasMessage("聊天模型配置无效").hasNoCause();
    }

    @Test
    void invalidUpperBoundsAndChangedPolicyIdentityFailClosed() {
        var policy = mock(ChatTokenBudget.class);
        when(policy.identity()).thenReturn(ID);
        when(policy.contextWindowTokens()).thenReturn(16000L);
        var binding = new ChatBudgetBinding(ID, List.of(policy));
        when(policy.inputTokenUpperBound(any())).thenReturn(-1L);
        assertThatThrownBy(() -> binding.upperBound(prepared())).hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
        when(policy.inputTokenUpperBound(any())).thenThrow(new IllegalStateException("secret"));
        assertThatThrownBy(() -> binding.upperBound(prepared())).hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
        when(policy.identity()).thenReturn(new ChatModelIdentity(ID.baseUrl(), "changed", ID.formatVersion()));
        assertThatThrownBy(() -> binding.upperBound(prepared())).hasMessage("BUDGET_UNAVAILABLE").hasNoCause();
    }
}
