package com.zhishiku.kb.app.conversation;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.auth.service.ConversationSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class ConversationSessionTest {
    @Test
    void historyNeedsLiveTokenAndNewLoginKeepsStableUser() {
        var gate = mock(ConversationIdentityGate.class);
        var service = new ConversationSessionService(gate);
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(() -> StpUtil.getLoginIdByToken("old")).thenReturn("1");
            auth.when(() -> StpUtil.getLoginIdByToken("new")).thenReturn("1");
            assertThat(service.user("old")).isEqualTo(1);
            assertThat(service.user("new")).isEqualTo(1);
            auth.when(() -> StpUtil.getLoginIdByToken("old")).thenReturn(null);
            assertThatThrownBy(() -> service.user("old")).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
            assertThatThrownBy(() -> service.user(null)).isInstanceOf(ResponseStatusException.class);
        }
    }
}

