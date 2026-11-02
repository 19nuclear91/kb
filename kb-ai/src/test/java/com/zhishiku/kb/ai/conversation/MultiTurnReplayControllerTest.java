package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiTurnReplayControllerTest {
    @Test
    void budgetFailedReplayStillReturnsSavedStateWithNoCurrentBudgetCapability() {
        var fixture = new MultiTurnChatServiceTest();
        fixture.activeModel = mock(MultiTurnModel.class);
        when(fixture.activeModel.budgetCapability()).thenThrow(new IllegalStateException("private-budget"));
        when(fixture.app.check(9, "q", "old", 0, "token"))
                .thenReturn(Mono.just(new BeginResult(9, fixture.key.turnId(), 1, "FAILED", true, null)));
        var request = new MultiTurnChatController.TurnRequest();
        request.setQuestion("q");
        request.setRequestId("old");
        request.setExpectedLastTurnSeq(0L);
        var response = new MultiTurnChatController(fixture.service(true), fixture.properties)
                .turn(9, request, "token", null).block();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(new TurnReplay(9, fixture.key.turnId(), 1, "FAILED", true));
        verify(fixture.activeModel, never()).budgetCapability();
        verify(fixture.activeModel, never()).generate(any());
        verify(fixture.quota, never()).acquire(anyString(), anyInt());
        verify(fixture.retrieval, never()).retrieve(anyString(), anyString());
        verify(fixture.app, never()).begin(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

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
