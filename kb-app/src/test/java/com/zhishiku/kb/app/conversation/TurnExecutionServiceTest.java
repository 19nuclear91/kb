package com.zhishiku.kb.app.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.conversation.mapper.ConversationMapper;
import com.zhishiku.kb.app.conversation.model.BatchRow;
import com.zhishiku.kb.app.conversation.model.CitationView;
import com.zhishiku.kb.app.conversation.model.ConversationRow;
import com.zhishiku.kb.app.conversation.model.ExecutionKey;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import com.zhishiku.kb.app.conversation.model.SourceRow;
import com.zhishiku.kb.app.conversation.model.TurnRow;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import com.zhishiku.kb.app.conversation.service.ConversationService;
import com.zhishiku.kb.app.conversation.service.TurnExecutionService;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.conversation.SourceDeclaration;
import com.zhishiku.kb.common.conversation.SourceKey;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.zhishiku.kb.app.conversation.ConversationServiceTest.assertStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnExecutionServiceTest {
    final ConversationMapper mapper = mock(ConversationMapper.class);
    final ConversationIdentityGate identity = mock(ConversationIdentityGate.class);
    final DocumentVersionAccessService documents = mock(DocumentVersionAccessService.class);
    final ConversationProperties properties = new ConversationProperties();
    final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    final ConversationService reads = new ConversationService(mapper, identity, documents, properties, clock);
    final TurnExecutionService service = new TurnExecutionService(mapper, reads, identity, documents, properties,
            new ObjectMapper(), clock);
    final ConversationRow conversation = new ConversationRow();

    TurnExecutionServiceTest() {
        conversation.setId(9L);
        conversation.setOwnerUserId(1);
        conversation.setTitle("title");
        conversation.setCreatedAt(Instant.EPOCH);
        when(mapper.lock(9)).thenReturn(conversation);
        when(mapper.find(9)).thenReturn(conversation);
        when(identity.require(1)).thenReturn(mock(PermissionService.PrincipalHandle.class));
        when(documents.visible(any(), any())).thenAnswer(call -> call.getArgument(1));
        when(mapper.insertTurn(any())).thenAnswer(call -> {
            var turn = (TurnRow) call.getArgument(0);
            when(mapper.lockTurn(turn.getId())).thenReturn(turn);
            when(mapper.turn(turn.getId())).thenReturn(turn);
            return 1;
        });
        when(mapper.updateConversation(any())).thenReturn(1);
        when(mapper.updateTurn(any())).thenReturn(1);
        when(mapper.insertBatch(any())).thenReturn(1);
        when(mapper.insertCitation(any())).thenReturn(1);
        var stored = new HashMap<UUID, List<SourceRow>>();
        when(mapper.insertSource(any())).thenAnswer(call -> {
            var source = (SourceRow) call.getArgument(0);
            var list = stored.computeIfAbsent(source.getTurnId(), id -> new ArrayList<>());
            list.add(source);
            when(mapper.sources(source.getTurnId())).thenReturn(list);
            return 1;
        });
        when(mapper.insertSettlement(any())).thenAnswer(call -> {
            var row = (SettlementRow) call.getArgument(0);
            when(mapper.settlement(row.getTurnId())).thenReturn(row);
            return 1;
        });
        when(mapper.updateSettlement(any())).thenReturn(1);
    }

    ExecutionKey begin() {
        return service.begin(1, 9, "问题", "r", 0L).execution();
    }

    TurnRow turn(ExecutionKey key) {
        return mapper.turn(key.turnId());
    }

    @Test
    void observationRunsAfterBusinessWritesAndScrubbing() {
        var observer = mock(com.zhishiku.kb.app.aiusage.service.AiMultiObservation.class);
        service.setObservation(observer);
        var key = begin();
        var order = inOrder(mapper, observer);
        order.verify(mapper).insertTurn(any());
        order.verify(mapper).updateConversation(any());
        order.verify(observer).begin(org.mockito.ArgumentMatchers.eq(1L), any());
        org.mockito.Mockito.clearInvocations(mapper, observer);
        service.delete(1, 9);
        order = inOrder(mapper, observer);
        order.verify(mapper).updateTurn(any());
        order.verify(mapper).clearCitations(9);
        order.verify(mapper).clearSources(9);
        order.verify(mapper).scrubTurns(9);
        order.verify(mapper).updateConversation(any());
        order.verify(observer).finished(any(), org.mockito.ArgumentMatchers.isNull());
        order.verifyNoMoreInteractions();
    }

    @Test
    void beginIsSingleFlightAndReplayPrecedesExpectedSequence() {
        var result = service.begin(1, 9, "问题", "r", 0L);
        var row = turn(result.execution());
        when(mapper.request(9, "r")).thenReturn(row);
        var replay = service.begin(1, 9, "问题", "r", 0L);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.execution()).isNull();
        assertThat(result.execution().ticket()).hasSize(43);
        assertStatus(() -> service.begin(1, 9, "不同", "r", 0L), 409);
        assertStatus(() -> service.begin(1, 9, "问题", "new", 1L), 409);
        var order = inOrder(mapper);
        order.verify(mapper).lock(9);
        order.verify(mapper).request(9, "r");
        order.verify(mapper).insertTurn(any());
        order.verify(mapper).updateConversation(any());
    }

    @Test
    void sealIncludesSelectedParentAndOnlyNewCitationsThenIdempotentlyRejectsConflicts() {
        var key = begin();
        var parent = new TurnRow();
        parent.setId(UUID.randomUUID());
        parent.setConversationId(9);
        parent.setStatus("COMPLETED");
        parent.setTurnSeq(0);
        parent.setAnswerKind("MODEL");
        parent.setSourceSealed(true);
        Set<SourceKey> sources = Set.of(new SourceKey(1, 2));
        parent.setExpectedSourceCount(1);
        parent.setSourceDigest(SourceDeclaration.digest(sources));
        when(mapper.turn(parent.getId())).thenReturn(parent);
        var source = new SourceRow();
        source.setTurnId(parent.getId());
        source.setDocumentId(1);
        source.setVersionId(2);
        when(mapper.sources(parent.getId())).thenReturn(List.of(source));
        var citation = new CitationView(3, 4, 5, "title", 1, null, null, null, null, null, null, null, null, false);
        service.seal(1, key, "MODEL", List.of(parent.getId()), List.of(citation));
        assertThat(turn(key).getExpectedSourceCount()).isEqualTo(2);
        verify(mapper).insertCitation(any());
        service.seal(1, key, "MODEL", List.of(parent.getId()), List.of(citation));
        assertStatus(() -> service.seal(1, key, "NO_RESULTS", List.of(), List.of()), 409);
    }

    @Test
    void appendRejectsUnsealedJumpAndChangedReplayAndRetainsUnicodePosition() {
        var key = begin();
        assertStatus(() -> service.append(key, 1, "😀"), 400);
        service.seal(1, key, "MODEL", List.of(), List.of(new CitationView(1, 2, 3, "title", null, null, null, null,
                null, null, null, null, null, false)));
        assertStatus(() -> service.append(key, 2, "😀"), 409);
        assertThat(service.append(key, 1, "😀")).isEqualTo(1);
        var batch = new BatchRow();
        batch.setDigest(RequestSignature.digest("😀"));
        when(mapper.batch(key.turnId(), 1)).thenReturn(batch);
        assertThat(service.append(key, 1, "😀")).isEqualTo(1);
        assertThat(turn(key).getSavedCodepoints()).isEqualTo(1);
        assertStatus(() -> service.append(key, 1, "other"), 409);
        assertStatus(() -> service.append(key, 2, "a".repeat(1025)), 400);
        when(mapper.updateTurn(any())).thenReturn(0);
        assertStatus(() -> service.append(key, 2, "x"), 503);
    }

    @Test
    void reservationDispatchTerminalUsageAndCloseReplayAreBound() {
        var key = begin();
        service.seal(1, key, "MODEL", List.of(), List.of(new CitationView(1, 2, 3, "title", null, null, null, null,
                null, null, null, null, null, false)));
        assertStatus(() -> service.dispatch(key), 409);
        UUID lease = UUID.randomUUID(), event = UUID.randomUUID();
        service.reserve(key, 10, lease, event, 12000);
        service.reserve(key, 10, lease, event, 12000);
        assertStatus(() -> service.reserve(key, 20, lease, event, 12000), 409);
        service.dispatch(key);
        service.append(key, 1, "答案");
        var result = service.finish(key, "COMPLETED", 1, true, 14000L, 10L);
        assertThat(result.getChargedTokens()).isEqualTo(14010);
        assertThat(result.getState()).isEqualTo("PENDING");
        assertThat(turn(key).getStatus()).isEqualTo("COMPLETED");
        assertThat(conversation.getActiveTurnId()).isNull();
        assertThat(service.finish(key, "COMPLETED", 1, true, 14000L, 10L)).isSameAs(result);
        assertStatus(() -> service.finish(key, "CANCELLED", 1, false, null, null), 409);
        assertStatus(() -> service.finish(key, "COMPLETED", 1, true, 1L, 1L), 409);
        assertStatus(() -> service.append(key, 2, "late"), 409);
    }

    @Test
    void cancelledAndExpiredAreUnknownAfterDispatchAndNeverRevive() {
        var key = begin();
        var row = turn(key);
        row.setSourceSealed(true);
        row.setAnswerKind("MODEL");
        service.reserve(key, 10, UUID.randomUUID(), UUID.randomUUID(), 12000);
        service.dispatch(key);
        var result = service.finish(key, "CANCELLED", 0, false, null, null);
        assertThat(result.getPromptTokens()).isNull();
        assertThat(result.getChargedTokens()).isEqualTo(12000);
        assertStatus(() -> service.finish(key, "COMPLETED", 0, true, 1L, 1L), 409);
        conversation.setActiveTurnId(null);
        var next = service.begin(1, 9, "next", "r2", 1L).execution();
        var expiredRow = turn(next);
        expiredRow.setLeaseUntil(Instant.EPOCH);
        assertStatus(() -> service.renew(next), 409);
        service.recover(9);
        assertThat(expiredRow.getStatus()).isEqualTo("INTERRUPTED");
        assertThat(conversation.getActiveTurnId()).isNull();
    }

    @Test
    void deletionScrubsTextRevokesEpochAndPreservesSettlementMetadata() {
        var key = begin();
        service.reserve(key, 10, UUID.randomUUID(), UUID.randomUUID(), 12000);
        service.delete(1, 9);
        service.delete(1, 9);
        assertThat(conversation.getTitle()).isNull();
        assertThat(conversation.getExecutionEpoch()).isEqualTo(1);
        verify(mapper).clearCitations(9);
        verify(mapper).clearSources(9);
        verify(mapper).scrubTurns(9);
        assertThat(mapper.settlement(key.turnId()).getState()).isEqualTo("PENDING");
        assertStatus(() -> service.append(key, 1, "late"), 404);
        assertStatus(() -> service.finish(key, "COMPLETED", 0, false, null, null), 404);
        assertStatus(() -> service.begin(1, 9, "问题", "r", 0L), 404);
        assertStatus(() -> service.delete(2, 9), 404);
    }

    @Test
    void methodsDeclareShortTransactionalBoundariesAndWrongTicketFails() throws Exception {
        for (var method : TurnExecutionService.class.getDeclaredMethods()) {
            if (Set.of("begin", "seal", "append", "renew", "reserve", "dispatch", "finish", "recover", "delete").contains(method.getName()))
                assertThat(method.getAnnotation(Transactional.class)).isNotNull();
        }
        var key = begin();
        var wrong = new ExecutionKey(9, key.turnId(), 1, 0, "wrong");
        assertStatus(() -> service.renew(wrong), 409);
        var other = new ExecutionKey(9, UUID.randomUUID(), 1, 0, key.ticket());
        assertStatus(() -> service.renew(other), 409);
    }
}

