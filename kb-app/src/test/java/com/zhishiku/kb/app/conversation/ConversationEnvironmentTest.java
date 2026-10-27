package com.zhishiku.kb.app.conversation;

import com.zhishiku.kb.app.config.ConversationEnvironmentValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationEnvironmentTest {
    @Test
    void validationRunsDirectlyBeforeAnyConnectionsAndRequiresAppDeliveryEndpoint() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThatCode(() -> ConversationEnvironmentValidator.validate(environment)).doesNotThrowAnyException();
        environment.setProperty("kb.conversation.enabled", "true");
        assertThatThrownBy(() -> ConversationEnvironmentValidator.validate(environment)).isInstanceOf(IllegalArgumentException.class);
        environment.setProperty("kb.conversation.secret", "0123456789abcdef0123456789abcdef");
        assertThatThrownBy(() -> ConversationEnvironmentValidator.validate(environment)).isInstanceOf(IllegalArgumentException.class);
        environment.setProperty("kb.conversation.ai-base-url", "http://ai:8082");
        assertThatCode(() -> ConversationEnvironmentValidator.validate(environment)).doesNotThrowAnyException();
        environment.setProperty("kb.conversation.renewal-ms", "120000");
        assertThatThrownBy(() -> ConversationEnvironmentValidator.validate(environment)).isInstanceOf(IllegalArgumentException.class);
    }
}

