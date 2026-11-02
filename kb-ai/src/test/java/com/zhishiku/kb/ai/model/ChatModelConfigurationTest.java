package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.chat.ChatModelClient;
import com.zhishiku.kb.ai.conversation.MultiTurnModel;
import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatModelConfigurationTest {
    private static MockEnvironment environment() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment.withProperty("kb.ai.chat-model.enabled", "true")
                .withProperty("kb.ai.chat-model.base-url", "https://model.invalid/v1")
                .withProperty("kb.ai.chat-model.api-key", "secret")
                .withProperty("kb.ai.chat-model.model", "text-model");
    }

    @Test
    void factoriesCreateExpectedTypesWithoutStartingAContainerOrNetwork() {
        var configuration = new ChatModelConfiguration(environment());
        var renderer = mock(ChatPromptRenderer.class);
        var transport = mock(OpenAiChatTransport.class);
        var budget = mock(ChatBudgetBinding.class);
        assertThat(configuration.openAiChatModelClient(renderer, transport)).isInstanceOf(OpenAiChatModelClient.class);
        assertThat(configuration.openAiMultiTurnModel(renderer, transport, budget)).isInstanceOf(OpenAiMultiTurnModel.class);
        assertThat(configuration.chatPromptRenderer()).isInstanceOf(ChatPromptRenderer.class);
        assertThat(configuration.openAiSseDecoder()).isInstanceOf(OpenAiSseDecoder.class);
        assertThat(configuration.openAiChatTransport(mock(OpenAiSseDecoder.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.zhishiku.kb.ai.telemetry.ModelTelemetry.class))).isInstanceOf(OpenAiChatTransport.class);
    }

    @Test
    void singleFactoryPassesConfiguredOutputLimit() {
        var configuration = new ChatModelConfiguration(environment()
                .withProperty("kb.ai.chat-model.single-max-output-tokens", "321"));
        var renderer = mock(ChatPromptRenderer.class);
        var transport = mock(OpenAiChatTransport.class);
        var request = mock(PreparedChatRequest.class);
        List<RetrievalClient.Citation> materials = List.of();
        when(renderer.single("q", materials, 321)).thenReturn(request);
        when(transport.stream(request)).thenReturn(Flux.just(new ModelEvent.Delta("answer")));
        StepVerifier.create(configuration.openAiChatModelClient(renderer, transport).stream("q", materials))
                .expectNext("answer").verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void budgetFactoryHasNoImplicitEstimatorAndRejectsDuplicates() {
        var configuration = new ChatModelConfiguration(environment());
        ObjectProvider<ChatTokenBudget> policies = mock(ObjectProvider.class);
        when(policies.orderedStream()).thenReturn(Stream.empty());
        assertThat(configuration.chatBudgetPolicy(policies).capability()).isEqualTo(BudgetCapability.unavailable());
        var policy = mock(ChatTokenBudget.class);
        when(policy.identity()).thenReturn(ChatModelIdentity.of(ChatModelPropertiesTest.configured()));
        when(policy.contextWindowTokens()).thenReturn(16000L);
        when(policies.orderedStream()).thenReturn(Stream.of(policy, policy));
        assertThatThrownBy(() -> configuration.chatBudgetPolicy(policies)).hasMessage("聊天模型配置无效");
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitEstimationDoesNotEvenResolveStrictPolicies() {
        var configuration = new ChatModelConfiguration(environment()
                .withProperty("kb.ai.chat-model.budget.mode", "estimated")
                .withProperty("kb.ai.chat-model.budget.context-window-tokens", "16000"));
        ObjectProvider<ChatTokenBudget> policies = mock(ObjectProvider.class);
        var budget = configuration.chatBudgetPolicy(policies);
        assertThat(budget).isInstanceOf(ConfiguredEstimatedTokenBudget.class);
        assertThat(budget.count(ChatTokenBudgetTest.prepared()).kind()).isEqualTo(BudgetKind.ESTIMATED);
        verifyNoInteractions(policies);
    }

    @Test
    void conditionalContractsKeepCustomModelsIndependentAndDisabledAdapterInactive() throws Exception {
        // 直接核对工厂的装配条件，不启动 Spring 容器或替换用户模型。
        assertThat(ChatModelConfiguration.class.getDeclaredMethod("openAiChatModelClient",
                ChatPromptRenderer.class, OpenAiChatTransport.class).getAnnotation(ConditionalOnMissingBean.class).value())
                .containsExactly(ChatModelClient.class);
        assertThat(ChatModelConfiguration.class.getDeclaredMethod("openAiMultiTurnModel",
                ChatPromptRenderer.class, OpenAiChatTransport.class, ChatBudgetPolicy.class)
                .getAnnotation(ConditionalOnMissingBean.class).value()).containsExactly(MultiTurnModel.class);
        var enabled = ChatModelConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(enabled.prefix()).isEqualTo("kb.ai.chat-model");
        assertThat(enabled.name()).containsExactly("enabled");
        assertThat(enabled.havingValue()).isEqualTo("true");
        assertThat(enabled.matchIfMissing()).isFalse();
        assertThatThrownBy(() -> new ChatModelConfiguration(environment()
                .withProperty("kb.ai.chat-model.budget.mode", "estimated")))
                .hasMessage("聊天模型配置无效").hasNoCause();
    }

    @Test
    void rejectsInvalidConfigWithoutRawBindingErrorsAndLeavesDefaultsDisabled() {
        assertThatThrownBy(() -> new ChatModelConfiguration(environment()
                .withProperty("kb.ai.chat-model.api-key", ""))).hasMessage("聊天模型配置无效").hasNoCause();
        assertThatThrownBy(() -> new ChatModelConfiguration(environment()
                .withProperty("kb.ai.chat-model.output-token-field", "secret-invalid")))
                .hasMessage("聊天模型配置无效").hasNoCause();
        assertThat(ChatModelEnvironmentValidator.validate(new MockEnvironment()).isEnabled()).isFalse();
    }
}
