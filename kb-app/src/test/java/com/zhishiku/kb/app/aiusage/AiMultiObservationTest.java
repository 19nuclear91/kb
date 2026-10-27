package com.zhishiku.kb.app.aiusage;

import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.model.AiEventRow;
import com.zhishiku.kb.app.aiusage.service.AiMultiObservation;
import com.zhishiku.kb.app.aiusage.service.AiUsageWriter;
import com.zhishiku.kb.app.auth.service.AuthService;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import com.zhishiku.kb.app.conversation.model.TurnRow;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiMultiObservationTest {
    private final AiUsageWriter writer = mock(AiUsageWriter.class);
    private final AiUsageMapper mapper = mock(AiUsageMapper.class);
    private final AuthService auth = mock(AuthService.class);
    private final AiObservationProperties props = new AiObservationProperties(new MockEnvironment());
    private final AiMultiObservation observer = new AiMultiObservation(writer, mapper, props, auth);
    private final Instant time = Instant.parse("2026-10-24T00:00:00Z");
    private final UUID id = UUID.randomUUID();

    private AiCallRow row() {
        var row = new AiCallRow(); row.setCallId(id.toString()); row.setEpoch(1); row.setUserId(1); row.setDepartmentId(2); return row;
    }
    private TurnRow turn() {
        var turn = new TurnRow(); turn.setId(id); turn.setCreatedAt(time); turn.setUpdatedAt(time);
        turn.setFinishedAt(time); turn.setStatus("CANCELLED"); turn.setAnswerKind("MODEL");
        turn.setUsageStatus("UNKNOWN"); return turn;
    }
    private SettlementRow ledger() {
        var ledger = new SettlementRow(); ledger.setTurnId(id); ledger.setLeaseId(UUID.randomUUID());
        ledger.setUserId(1); ledger.setDepartmentId(2); ledger.setCreatedAt(time); ledger.setSettledAt(time);
        ledger.setReservedTokens(12000); ledger.setChargedTokens(12000); return ledger;
    }

    @Test
    void stableTurnIdentityAndBoundDepartmentAreProjectedWithoutNetwork() {
        props.setEnabled(true); when(mapper.call(id.toString())).thenReturn(row());
        when(auth.quotaPrincipal(1)).thenReturn(new QuotaPrincipal(1, 2));
        observer.begin(1, turn());
        verify(writer).registerMulti(new AiObservation.Registration(1, id, time, "MULTI", id), 1, 2);
        var ledger = ledger(); observer.reserved(ledger); observer.dispatched(turn());
        var events = ArgumentCaptor.forClass(AiObservation.Event.class);
        verify(writer, times(2)).accept(events.capture());
        assertThat(events.getAllValues()).extracting(AiObservation.Event::kind).containsExactly("RESERVED", "DISPATCHED");
        assertThat(events.getAllValues()).allSatisfy(event -> assertThat(event.callId()).isEqualTo(id));
        ledger.setDepartmentId(3);
        assertThatThrownBy(() -> observer.reserved(ledger)).isInstanceOf(com.zhishiku.kb.common.api.ApiException.class);
    }

    @Test
    void stoppingCollectionKeepsExistingFinalizationAndSettlementButNeverBackfillsOldTurns() {
        props.setEnabled(false); observer.begin(1, turn()); verifyNoInteractions(auth, writer);
        observer.finished(turn(), ledger()); observer.settled(ledger()); verifyNoInteractions(writer);
        when(mapper.call(id.toString())).thenReturn(row());
        var ledger = ledger(); observer.finished(turn(), ledger);
        ledger.setState("SETTLED"); observer.settled(ledger);
        var events = ArgumentCaptor.forClass(AiObservation.Event.class); verify(writer, times(2)).accept(events.capture());
        assertThat(events.getAllValues().get(0).plannedChargedTokens()).isEqualTo(12000L);
        assertThat(events.getAllValues().get(0).usageStatus()).isEqualTo("UNKNOWN");
        assertThat(events.getAllValues().get(0).promptTokens()).isNull();
        assertThat(events.getAllValues().get(0).settlementStatus()).isEqualTo("PENDING");
        assertThat(events.getAllValues().get(1).kind()).isEqualTo("SETTLED");
        when(mapper.phase(id.toString(), "SETTLED")).thenReturn(new AiEventRow());
        observer.settled(ledger); verify(writer, times(2)).accept(any());
    }

    @Test
    void missingLeaseStaysReviewUnlessQuotaWasExplicitlyRejected() {
        var row = row(); when(mapper.call(id.toString())).thenReturn(row);
        observer.finished(turn(), null);
        var event = ArgumentCaptor.forClass(AiObservation.Event.class); verify(writer).accept(event.capture());
        assertThat(event.getValue().settlementStatus()).isEqualTo("REVIEW");
        assertThat(event.getValue().plannedChargedTokens()).isNull();
        clearInvocations(writer); row.setResultCode("QUOTA_REJECTED");
        var turn = turn(); turn.setUsageStatus("NOT_INVOKED"); turn.setPromptTokens(0L); turn.setCompletionTokens(0L);
        observer.finished(turn, null); verify(writer).accept(event.capture());
        assertThat(event.getValue().generationStatus()).isEqualTo("REJECTED");
        assertThat(event.getValue().settlementStatus()).isEqualTo("NOT_REQUIRED");
    }
}
