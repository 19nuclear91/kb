package com.zhishiku.kb.app.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.conversation.mapper.ConversationMapper;
import com.zhishiku.kb.app.conversation.model.ConversationRow;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import com.zhishiku.kb.app.conversation.model.TurnRow;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import com.zhishiku.kb.app.conversation.service.ConversationService;
import com.zhishiku.kb.app.conversation.service.TurnExecutionService;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnRetrievalFailureTest {
    @Test
    void unsealedFailureIsFailedWithPendingZeroSettlementAndCannotBeRewrittenByCancellation() {
        var mapper = mock(ConversationMapper.class);
        var reads = mock(ConversationService.class);
        var row = new ConversationRow();
        row.setId(9L);
        row.setOwnerUserId(1);
        when(reads.owned(1, 9, true)).thenReturn(row);
        var turn = new AtomicReference<TurnRow>();
        when(mapper.insertTurn(any())).thenAnswer(call -> {
            TurnRow value = call.getArgument(0);
            turn.set(value);
            when(mapper.lockTurn(value.getId())).thenReturn(value);
            return 1;
        });
        when(mapper.updateConversation(any())).thenReturn(1);
        when(mapper.updateTurn(any())).thenReturn(1);
        when(mapper.updateSettlement(any())).thenReturn(1);
        when(mapper.insertSettlement(any())).thenAnswer(call -> {
            SettlementRow value = call.getArgument(0);
            when(mapper.settlement(value.getTurnId())).thenReturn(value);
            return 1;
        });
        var service = new TurnExecutionService(mapper, reads, mock(ConversationIdentityGate.class),
                mock(DocumentVersionAccessService.class), new ConversationProperties(), new ObjectMapper(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var key = service.begin(1, 9, "q", "request", 0L).execution();
        service.reserve(key, 10, UUID.randomUUID(), UUID.randomUUID(), 12000);
        var intent = service.finish(key, "FAILED", 0, false, null, null);
        assertThat(turn.get().isSourceSealed()).isFalse();
        assertThat(turn.get().isModelDispatchIntent()).isFalse();
        assertThat(turn.get().getStatus()).isEqualTo("FAILED");
        assertThat(turn.get().getAssistantText()).isNullOrEmpty();
        assertThat(intent.getState()).isEqualTo("PENDING");
        assertThat(intent.getUsageStatus()).isEqualTo("NOT_INVOKED");
        assertThat(intent.getChargedTokens()).isZero();
        assertThat(intent.getPromptTokens()).isZero();
        assertThat(intent.getCompletionTokens()).isZero();
        assertThat(service.finish(key, "FAILED", 0, false, null, null)).isSameAs(intent);
        assertThatThrownBy(() -> service.finish(key, "CANCELLED", 0, false, null, null)).isInstanceOf(RuntimeException.class);
        verify(mapper, never()).insertBatch(any());
        verify(mapper, never()).insertSource(any());
    }
}
