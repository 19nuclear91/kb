package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.chat.ChatModelClient;
import com.zhishiku.kb.ai.conversation.MultiTurnModel;
import com.zhishiku.kb.ai.telemetry.ModelTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

/** 自动配置在用户 Bean 定义之后处理，使既有部署方模型分别优先。 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kb.ai.chat-model", name = "enabled", havingValue = "true")
public class ChatModelConfiguration {
    private final ChatModelProperties properties;
    private final int totalSeconds;
    private final int reservationTokens;

    public ChatModelConfiguration(ConfigurableEnvironment environment) {
        properties = ChatModelEnvironmentValidator.validate(environment);
        totalSeconds = environment.getProperty("kb.ai.upstream-timeout", Integer.class, 60);
        reservationTokens = environment.getProperty("kb.ai.max-tokens-per-request", Integer.class, 12000);
    }

    @Bean
    ChatPromptRenderer chatPromptRenderer() {
        return new ChatPromptRenderer(properties);
    }

    @Bean
    OpenAiSseDecoder openAiSseDecoder() {
        return new OpenAiSseDecoder(properties);
    }

    @Bean
    OpenAiChatTransport openAiChatTransport(OpenAiSseDecoder decoder, ObjectProvider<ModelTelemetry> telemetry) {
        return new OpenAiChatTransport(properties, totalSeconds, OpenAiChatTransport.isolatedClient(properties), decoder,
                telemetry.getIfAvailable(() -> ModelTelemetry.NOOP));
    }

    @Bean
    ChatBudgetPolicy chatBudgetPolicy(ObjectProvider<ChatTokenBudget> policies) {
        if ("estimated".equals(properties.getBudget().getMode())) {
            return new ConfiguredEstimatedTokenBudget(ChatModelIdentity.of(properties), properties.getBudget());
        }
        return new ChatBudgetBinding(ChatModelIdentity.of(properties), policies.orderedStream().toList());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kb.metadata-suggestions", name = "enabled", havingValue = "true")
    MetadataSuggestionModel metadataSuggestionModel(OpenAiChatTransport transport, ObjectProvider<ChatTokenBudget> policies) {
        var identity = new ChatModelIdentity(properties.rootUri(), properties.getModel(), MetadataSuggestionModel.FORMAT);
        ChatBudgetPolicy budget = "estimated".equals(properties.getBudget().getMode())
                ? new ConfiguredEstimatedTokenBudget(identity, properties.getBudget())
                : new ChatBudgetBinding(identity, policies.orderedStream().toList());
        return new MetadataSuggestionModel(properties, transport, budget, reservationTokens);
    }

    @Bean
    @ConditionalOnMissingBean(ChatModelClient.class)
    ChatModelClient openAiChatModelClient(ChatPromptRenderer renderer, OpenAiChatTransport transport) {
        return new OpenAiChatModelClient(renderer, transport, properties.getSingleMaxOutputTokens());
    }

    @Bean
    @ConditionalOnMissingBean(MultiTurnModel.class)
    MultiTurnModel openAiMultiTurnModel(ChatPromptRenderer renderer, OpenAiChatTransport transport, ChatBudgetPolicy budget) {
        return new OpenAiMultiTurnModel(renderer, transport, budget, reservationTokens);
    }
}
