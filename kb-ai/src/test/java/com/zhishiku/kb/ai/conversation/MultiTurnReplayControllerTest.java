package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiTurnReplayControllerTest {
    @Test
    void failedReplayReturnsOnlySavedIdentityAndNeverOpensAStream() {
        var service = mock(MultiTurnChatService.class);
        var prepared = mock(MultiTurnChatService.Prepared.class);
        var id = UUID.randomUUID();
        when(prepared.result()).thenReturn(new BeginResult(9, id, 1, "FAILED", true, null));
        when(service.prepare(9, "q", "old", 0, "token")).thenReturn(Mono.just(prepared));
        var request = new MultiTurnChatController.TurnRequest();
        request.setQuestion("q");
        request.setRequestId("old");
        request.setExpectedLastTurnSeq(0L);
        var response = new MultiTurnChatController(service, new ConversationProperties())
                .turn(9, request, "token", null).block();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(new TurnReplay(9, id, 1, "FAILED", true));
        verify(service, never()).stream(any());
    }
}
