package com.zhishiku.kb.app.conversation;

import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.auth.service.ConversationSessionService;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import com.zhishiku.kb.common.security.AccountRevokedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationSessionTest {
    @Test
    void historyNeedsCurrentEpochAndNewLoginKeepsStableUser() {
        var gate = mock(ConversationIdentityGate.class);
        var sessions = mock(EpochSessionService.class);
        var service = new ConversationSessionService(gate, sessions);
        when(sessions.check("old")).thenReturn(new EpochSessionService.Session(1, 1, EpochSessionService.Method.LOCAL, false));
        when(sessions.check("new")).thenReturn(new EpochSessionService.Session(1, 3, EpochSessionService.Method.LOCAL, false));
        assertThat(service.user("old")).isEqualTo(1);
        assertThat(service.user("new")).isEqualTo(1);
        doThrow(IdentityException.unauthorized("旧代次")).when(sessions).check("old");
        doThrow(IdentityException.unauthorized("无会话")).when(sessions).check(null);
        assertThatThrownBy(() -> service.user("old")).isInstanceOfSatisfying(AccountRevokedException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(401));
        assertThatThrownBy(() -> service.user(null)).isInstanceOf(AccountRevokedException.class);
        assertThat(service.user("new")).isEqualTo(1);
    }
}
