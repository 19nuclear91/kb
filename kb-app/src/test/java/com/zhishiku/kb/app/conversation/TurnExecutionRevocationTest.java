package com.zhishiku.kb.app.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.AuthenticationIdentity;
import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.conversation.model.CitationView;
import com.zhishiku.kb.app.conversation.model.ExecutionKey;
import com.zhishiku.kb.app.conversation.service.TurnExecutionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.zhishiku.kb.app.conversation.ConversationServiceTest.assertStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnExecutionRevocationTest {
    private final TurnExecutionServiceTest fixture = new TurnExecutionServiceTest();
    private final IdentityDepartmentMapper identities = mock(IdentityDepartmentMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final ConversationIdentityGate gate = new ConversationIdentityGate(permissions, identities);
    private final TurnExecutionService service = new TurnExecutionService(fixture.mapper, fixture.reads, gate,
            fixture.documents, fixture.properties, new ObjectMapper(), fixture.clock);
    private IdentityRecord user = user(1, true, "CLEAR");

    TurnExecutionRevocationTest() {
        when(identities.findIdentityForUpdate(1)).thenAnswer(call -> user);
        when(permissions.principalOf(1)).thenReturn(mock(PermissionService.PrincipalHandle.class));
        when(identities.findMustChangePasswordById(1)).thenReturn(false);
    }

    @Test
    void everyGeneratingWriteRejectsOldEpochBeforeConversationOrMaterialAccess() {
        var key = begin();
        user = user(2, false, "OFFBOARDED");
        clearInvocations(fixture.mapper, fixture.documents, permissions);
        assertStatus(() -> service.reserve(key, 10, UUID.randomUUID(), UUID.randomUUID(), 12000), 401);
        assertStatus(() -> service.dispatch(key), 401);
        assertStatus(() -> service.seal(1, key, "NO_RESULTS", List.of(), List.of()), 401);
        assertStatus(() -> service.append(key, 1, "迟到正文"), 401);
        assertStatus(() -> service.renew(key), 401);
        assertStatus(() -> service.finish(key, "COMPLETED", 0, false, null, null), 401);
        verify(fixture.mapper, never()).lock(9);
        verify(fixture.mapper, never()).updateTurn(any());
        verify(fixture.documents, never()).visible(any(), any());
        verify(permissions, never()).principalOf(1);
    }

    @Test
    void restorationCannotReviveOldTicketAndChangingItsEpochCannotForgeAnotherExecution() {
        var key = begin();
        user = user(3, true, "CLEAR");
        assertStatus(() -> service.begin(1, 1, 9, "旧请求", "old", 1L), 401);
        assertStatus(() -> service.renew(key), 401);
        var forged = new ExecutionKey(9, key.turnId(), 1, key.executionEpoch(), 3, key.ticket());
        assertStatus(() -> service.renew(forged), 409);
        assertStatus(() -> service.finish(forged, "CANCELLED", 0, false, null, null, "ACCOUNT_REVOKED"), 409);
        assertThat(fixture.turn(key).getAuthEpoch()).isEqualTo(1);
    }

    @Test
    void cancelledUninvokedTurnReleasesOnlyItsFixedZeroIntentWithoutCurrentIdentity() {
        var key = begin();
        service.reserve(key, 10, UUID.randomUUID(), UUID.randomUUID(), 12000);
        user = user(2, false, "OFFBOARDED");
        clearInvocations(identities, permissions);
        var intent = service.finish(key, "CANCELLED", 0, false, null, null, "ACCOUNT_REVOKED");
        assertThat(intent.getUsageStatus()).isEqualTo("NOT_INVOKED");
        assertThat(intent.getChargedTokens()).isZero();
        assertThat(intent.getState()).isEqualTo("PENDING");
        assertThat(fixture.turn(key).getCancellationReason()).isEqualTo("ACCOUNT_REVOKED");
        assertThat(service.finish(key, "CANCELLED", 0, false, null, null, "ACCOUNT_REVOKED")).isSameAs(intent);
        verify(identities, never()).findIdentityForUpdate(1);
        verify(permissions, never()).principalOf(1);
        assertStatus(() -> service.finish(key, "COMPLETED", 0, false, null, null, "ACCOUNT_REVOKED"), 400);
    }

    @Test
    void revokedInFlightTurnPreservesCommittedPrefixAndUnknownUsageDespiteLostAppendResponse() {
        var key = begin();
        service.reserve(key, 10, UUID.randomUUID(), UUID.randomUUID(), 12000);
        service.seal(1, key, "MODEL", List.of(), List.of(new CitationView(1, 2, 3, "标题", null, null,
                null, null, null, null, null, null, null, false)));
        service.dispatch(key);
        service.append(key, 1, "已确认前缀");
        user = user(2, false, "OFFBOARDED");
        fixture.turn(key).setLeaseUntil(Instant.EPOCH);
        var intent = service.finish(key, "CANCELLED", 0, false, null, null, "ACCOUNT_REVOKED");
        assertThat(fixture.turn(key).getAssistantText()).isEqualTo("已确认前缀");
        assertThat(fixture.turn(key).getLastBatchSeq()).isEqualTo(1);
        assertThat(intent.getGenerationStatus()).isEqualTo("CANCELLED");
        assertThat(intent.getUsageStatus()).isEqualTo("UNKNOWN");
        assertThat(intent.getChargedTokens()).isEqualTo(12000);
        assertThat(service.finish(key, "CANCELLED", 0, false, null, null, "ACCOUNT_REVOKED")).isSameAs(intent);
        assertStatus(() -> service.finish(key, "CANCELLED", 0, true, 1L, 1L, "ACCOUNT_REVOKED"), 409);
        when(fixture.mapper.ackSettlement(key.turnId(), intent.getIntentDigest(), fixture.clock.instant())).thenReturn(1);
        assertThat(service.ackSettlement(key.turnId(), intent.getIntentDigest())).isTrue();
        intent.setState("SETTLED");
        assertThat(service.ackSettlement(key.turnId(), intent.getIntentDigest())).isTrue();
        verify(fixture.mapper).ackSettlement(key.turnId(), intent.getIntentDigest(), fixture.clock.instant());
    }

    @Test
    void expiredRecoveryRecognizesPersistentRevocationEvenAfterRestorationAndKeepsFixedIntent() {
        var key = begin();
        service.reserve(key, 10, UUID.randomUUID(), UUID.randomUUID(), 12000);
        fixture.turn(key).setLeaseUntil(Instant.EPOCH);
        when(identities.findAuthenticationIdentity(1)).thenReturn(new AuthenticationIdentity(1, 3, true, true, false));
        service.recover(9);
        var row = fixture.turn(key);
        assertThat(row.getStatus()).isEqualTo("CANCELLED");
        assertThat(row.getCancellationReason()).isEqualTo("ACCOUNT_REVOKED");
        var intent = fixture.mapper.settlement(key.turnId());
        String original = intent.getIntentDigest();
        service.recover(9);
        assertThat(intent.getIntentDigest()).isEqualTo(original);
        assertThat(intent.getUsageStatus()).isEqualTo("NOT_INVOKED");
        verify(fixture.mapper).updateSettlement(intent);
    }

    @Test
    void dependencyFailureDoesNotInventAccountRevocationDuringRecovery() {
        var key = begin();
        fixture.turn(key).setLeaseUntil(Instant.EPOCH);
        when(identities.findAuthenticationIdentity(1)).thenReturn(new AuthenticationIdentity(1, 0, false, false, false));
        assertStatus(() -> service.recover(9), 503);
        assertThat(fixture.turn(key).getStatus()).isEqualTo("RUNNING");
        assertThat(fixture.turn(key).getCancellationReason()).isNull();
    }

    @Test
    void legalCompletionBeforeRevocationRemainsCompletedAndLocksIdentityFirst() {
        var key = begin();
        service.seal(1, key, "NO_RESULTS", List.of(), List.of());
        clearInvocations(identities, fixture.mapper);
        service.append(key, 1, com.zhishiku.kb.app.conversation.service.ConversationService.NO_RESULTS);
        var order = inOrder(identities, fixture.mapper);
        order.verify(identities).findIdentityForUpdate(1);
        order.verify(fixture.mapper).lock(9);
        order.verify(fixture.mapper).lockTurn(key.turnId());
        service.finish(key, "COMPLETED", 1, false, null, null);
        user = user(2, false, "OFFBOARDED");
        assertStatus(() -> service.finish(key, "CANCELLED", 1, false, null, null, "ACCOUNT_REVOKED"), 409);
        assertThat(fixture.turn(key).getStatus()).isEqualTo("COMPLETED");
    }

    private ExecutionKey begin() {
        return service.begin(1, 1, 9, "问题", "request", 0L).execution();
    }

    private static IdentityRecord user(long epoch, boolean enabled, String restriction) {
        return new IdentityRecord(1L, "user", "员工", 1L, (short) 1, enabled, 0L, null, null,
                true, epoch, restriction);
    }
}
