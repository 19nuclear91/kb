package com.zhishiku.kb.app.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.ConversationSessionService;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import com.zhishiku.kb.app.conversation.service.ConversationService;
import com.zhishiku.kb.app.conversation.service.InternalConversationService;
import com.zhishiku.kb.app.conversation.service.InternalRequestAuthenticator;
import com.zhishiku.kb.app.conversation.service.TurnExecutionService;
import com.zhishiku.kb.common.conversation.RequestSignature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

import static com.zhishiku.kb.app.conversation.ConversationServiceTest.assertStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalConversationSecurityTest {
    @Test
    void userTokenCannotReplaceServiceSignatureAndTamperedBodyIsRejectedBeforeSession() {
        var properties = new ConversationProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        var seen = new HashSet<String>();
        var authentication = new InternalRequestAuthenticator(properties, (a, b, c) -> seen.add(a + b), clock);
        var sessions = mock(ConversationSessionService.class);
        var reads = mock(ConversationService.class);
        var writes = mock(TurnExecutionService.class);
        var service = new InternalConversationService(authentication, sessions, reads, writes, new ObjectMapper());
        byte[] body = "{\"conversationId\":9}".getBytes(StandardCharsets.UTF_8);
        assertStatus(() -> service.invoke("prepare", "POST", "/internal/conversations/prepare", body, "owner-token",
                null), 403);
        verifyNoInteractions(sessions, reads, writes);
        var headers = RequestSignature.sign(properties.getSecret(), "kb-ai", "POST", "/internal/conversations/prepare"
                , body, "owner-token", clock);
        byte[] changed = "{\"conversationId\":10}".getBytes(StandardCharsets.UTF_8);
        assertStatus(() -> service.invoke("prepare", "POST", "/internal/conversations/prepare", changed, "owner-token"
                , headers), 403);
        when(sessions.user("owner-token")).thenReturn(1L);
        when(reads.prepareContext(1, 9)).thenReturn(List.of());
        assertThat(service.invoke("prepare", "POST", "/internal/conversations/prepare", body, "owner-token", headers)).isEqualTo(List.of());
        assertStatus(() -> service.invoke("prepare", "POST", "/internal/conversations/prepare", body, "owner-token",
                headers), 403);
        byte[] unknown = "{\"conversationId\":9,\"userId\":1}".getBytes(StandardCharsets.UTF_8);
        var signed = RequestSignature.sign(properties.getSecret(), "kb-ai", "POST", "/internal/conversations/prepare"
                , unknown, "owner-token", clock);
        assertStatus(() -> service.invoke("prepare", "POST", "/internal/conversations/prepare", unknown, "owner-token"
                , signed), 400);
    }
}

