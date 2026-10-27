package com.zhishiku.kb.app.conversation;

import com.zhishiku.kb.app.conversation.mapper.ConversationMapper;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import com.zhishiku.kb.app.conversation.service.ConversationMaintenanceScheduler;
import com.zhishiku.kb.app.conversation.service.ConversationMaintenanceService;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import com.zhishiku.kb.app.conversation.service.SettlementDeliveryClient;
import com.zhishiku.kb.app.conversation.service.TurnExecutionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMaintenanceTest {
    SettlementRow row(Instant finalized) {
        var row = new SettlementRow();
        row.setTurnId(UUID.randomUUID());
        row.setLeaseId(UUID.randomUUID());
        row.setEventId(UUID.randomUUID());
        row.setState("PENDING");
        row.setFinalizedAt(finalized);
        row.setAttempts(1);
        row.setClaimedUntil(Instant.EPOCH.plusSeconds(30));
        return row;
    }

    @Test
    void claimAndConditionalAckKeepStableIdentityAndStopBeyondSafeWindow() {
        var mapper = mock(ConversationMapper.class);
        var properties = new ConversationProperties();
        var service = new ConversationMaintenanceService(mapper, properties, Clock.fixed(Instant.EPOCH,
                ZoneOffset.UTC));
        var row = row(Instant.EPOCH);
        when(mapper.settlement(row.getTurnId())).thenReturn(row);
        when(mapper.deliveryResult(any(), anyInt(), any(), anyString(), any(), any())).thenReturn(1);
        UUID lease = row.getLeaseId(), event = row.getEventId();
        var claim = row.getClaimedUntil();
        service.acknowledge(row, false);
        assertThat(row.getNextAttemptAt()).isEqualTo(Instant.EPOCH.plusSeconds(2));
        assertThat(row.getState()).isEqualTo("PENDING");
        verify(mapper).deliveryResult(row.getTurnId(), 1, claim, "PENDING", Instant.EPOCH.plusSeconds(2), null);
        assertThat(row.getLeaseId()).isEqualTo(lease);
        assertThat(row.getEventId()).isEqualTo(event);
        var expired = row(Instant.EPOCH.minusSeconds(3600));
        when(mapper.settlement(expired.getTurnId())).thenReturn(expired);
        assertThat(service.retryable(expired)).isFalse();
        service.acknowledge(expired, false);
        assertThat(expired.getState()).isEqualTo("REVIEW");
        assertThat(SettlementDeliveryClient.payload(expired).keySet()).doesNotContain("question", "assistantText",
                "ticket", "token");
    }

    @Test
    void schedulerOnlyClosesExpiredAndDeliversExistingIntentWithoutUserOrModel() {
        var maintenance = mock(ConversationMaintenanceService.class);
        var execution = mock(TurnExecutionService.class);
        var delivery = mock(SettlementDeliveryClient.class);
        var expired = row(Instant.EPOCH);
        var safe = row(Instant.EPOCH);
        when(maintenance.expired()).thenReturn(List.of(9L));
        when(maintenance.claim()).thenReturn(List.of(expired, safe));
        when(maintenance.retryable(expired)).thenReturn(false);
        when(maintenance.retryable(safe)).thenReturn(true);
        new ConversationMaintenanceScheduler(maintenance, execution, delivery).tick();
        verify(execution).recover(9);
        verify(delivery, never()).deliver(expired);
        verify(maintenance).acknowledge(expired, false);
        verify(delivery).deliver(safe);
        verify(maintenance).acknowledge(safe, true);
    }
}

