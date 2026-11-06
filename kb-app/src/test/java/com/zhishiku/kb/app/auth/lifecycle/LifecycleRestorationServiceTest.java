package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.EmergencyIdentityFact;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.RoleSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LifecycleRestorationServiceTest {
    private final IdentityDepartmentMapper users = mock(IdentityDepartmentMapper.class);
    private final LifecycleMapper mappings = mock(LifecycleMapper.class);
    private final LifecycleInboxMapper inbox = mock(LifecycleInboxMapper.class);
    private final LifecycleAuthorizer auth = mock(LifecycleAuthorizer.class);
    private final DingTalkMembership member = mock(DingTalkMembership.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final SecurityLevelDictionary levels = mock(SecurityLevelDictionary.class);
    private final MutableClock clock = new MutableClock();
    private Map<String, LifecycleProof> proofs = new HashMap<>();
    private Map<String, LifecycleReceipt> receipts = new HashMap<>();
    private IdentityRecord user = identity(false, 2, 1, "REVIEW");
    private LifecycleAssignment mapping = assignment(1, LifecycleAssignment.Restriction.REVIEW, null);
    private LifecycleEventBoundary boundary = new LifecycleEventBoundary(11, 1, 0, 0, 1);
    private int cleanup;
    private boolean resolved;
    private final MemoryTransactions transactions = new MemoryTransactions();
    private final LifecycleRestorationService service = new LifecycleRestorationService(users, mappings, inbox, auth,
            DingTalkLifecycleSettingsTest.configured(false), member, new LifecycleProofService(mappings, clock),
            new LifecycleReceipts(mappings), new LifecycleStateService(users, mappings, audit), levels, transactions);

    @BeforeEach
    void repository() {
        when(auth.require("token", Permission.IDENTITY_LIFECYCLE_RESTORE))
                .thenReturn(new EpochSessionService.Session(8, 1, EpochSessionService.Method.SSO, false));
        when(users.findIdentity(7)).thenAnswer(call -> user);
        when(users.findIdentityForUpdate(7)).thenAnswer(call -> user);
        when(mappings.findAssignment(7)).thenAnswer(call -> mapping);
        when(mappings.lockAssignment(7)).thenAnswer(call -> mapping);
        when(inbox.boundary("ding-corp", "staff")).thenAnswer(call -> boundary);
        when(users.findDepartment(1)).thenReturn(new DepartmentRecord(1L, null, "D", "原部门", "/1/", true, 2L, null, null));
        when(users.findMustChangePasswordById(7)).thenReturn(false);
        when(levels.contains((short) 1)).thenReturn(true);
        when(mappings.userRoles(7)).thenReturn(List.of(new RoleSummary(2, "READ", "原角色")));
        when(users.findUserManagementPermissions(7)).thenReturn(List.of(new UserRolePermission(7L, 2L, "doc:read")));
        when(mappings.roleFactRevision(7)).thenReturn(1L);
        when(member.current("staff")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return currentEmployee();
        });
        when(mappings.insertProof(any())).thenAnswer(call -> {
            var proof = call.getArgument(0, LifecycleProof.class);
            proofs.put(proof.tokenHash(), proof);
            return 1;
        });
        when(mappings.lockProof(anyString())).thenAnswer(call -> proofs.get(call.getArgument(0)));
        when(mappings.consumeProof(anyString(), any())).thenAnswer(call -> {
            String key = call.getArgument(0);
            proofs.put(key, consumed(proofs.get(key), call.getArgument(1)));
            return 1;
        });
        when(mappings.invalidateRestorationProofs(7)).thenAnswer(call -> {
            proofs.replaceAll((key, proof) -> consumed(proof, clock.instant()));
            return proofs.size();
        });
        when(mappings.findReceipt(eq(8L), anyString())).thenAnswer(call -> receipts.get(call.getArgument(1)));
        when(mappings.insertReceipt(any())).thenAnswer(call -> {
            var receipt = call.getArgument(0, LifecycleReceipt.class);
            receipts.put(receipt.requestId(), receipt);
            return 1;
        });
        when(mappings.updateRestriction(eq(7L), anyLong(), any(), any(), any())).thenAnswer(call -> {
            assertThat(mapping.version()).isEqualTo(call.getArgument(1, Long.class));
            mapping = assignment(mapping.version() + 1, call.getArgument(2), call.getArgument(4));
            return 1;
        });
        when(users.setUserAccessState(eq(7L), anyBoolean(), anyBoolean(), anyLong(), anyLong())).thenAnswer(call -> {
            assertThat(user.getVersion()).isEqualTo(call.getArgument(4, Long.class));
            user = identity(call.getArgument(1), call.getArgument(3), user.getVersion() + 1, mapping.restriction().name());
            assertThat(user.getEnabled()).isEqualTo(call.getArgument(2));
            return 1;
        });
        when(users.enqueueSessionCleanup(eq(7L), anyLong())).thenAnswer(call -> {
            assertThat(call.getArgument(1, Long.class)).isEqualTo(2);
            return ++cleanup;
        });
        when(mappings.appendHistory(anyLong(), any(), anyString(), anyString())).thenReturn(1);
        when(inbox.resolveReview(eq(7L), eq(11L), any())).thenAnswer(call -> {
            resolved = true;
            return 1;
        });
        when(users.lifecycleProtectionRequired()).thenReturn(true);
        when(users.findEmergencyIdentities()).thenReturn(List.of(
                new EmergencyIdentityFact(8, 1L, "EMERGENCY", true, true, true, true, false, "CLEAR")));
        when(users.findEmergencyPermissions()).thenReturn(ManagementInvariant.EMERGENCY_PERMISSIONS.stream()
                .map(code -> new UserRolePermission(8L, 1L, code)).toList());
    }

    @Test
    void manualDisableSurvivesRestorationAndExactReplayDoesNotCallProviderAgain() {
        var verified = check();
        check();
        assertThat(verified.roles()).containsExactly(new RoleSummary(2, "READ", "原角色"));
        assertThat(verified.departmentName()).isEqualTo("原部门");
        assertThat(verified.manualEnabled()).isFalse();
        assertThat(verified.expiresAt()).isEqualTo(clock.instant().plusSeconds(300));
        clock.now = clock.now.plusSeconds(10);
        var request = confirmation(verified);
        var result = service.restore("token", 7, request);
        assertThat(result.outcome()).isEqualTo("RESTORED_MANUALLY_DISABLED");
        assertThat(result.enabled()).isFalse();
        assertThat(user.getAuthEpoch()).isEqualTo(3);
        assertThat(user.getDepartmentId()).isEqualTo(1);
        assertThat(user.getClearanceLevel()).isEqualTo((short) 1);
        assertThat(mapping.restoredAt()).isEqualTo(clock.instant());
        assertThat(mapping.lastEventId()).isEqualTo(11);
        assertThat(resolved).isTrue();
        assertThat(cleanup).isEqualTo(1);
        assertThat(proofs.values()).allMatch(proof -> proof.consumedAt() != null);
        when(member.current("staff")).thenThrow(ApiException.unavailable("平台故障"));
        assertThat(service.restore("token", 7, request)).isEqualTo(result);
        verify(member, times(3)).current("staff");
        assertThat(receipts.get("restore").response()).doesNotContain(verified.proof(), "staff");
        assertThatThrownBy(() -> service.restore("token", 7, new LifecycleRestorationService.Confirmation(1, 1,
                verified.proof(), "不同原因", "restore"))).isInstanceOf(IdentityException.class);
    }

    @Test
    void enabledRestorationStillRequiresNewEpochAndOrdinaryEnableCannotReplaceIt() {
        user = identity(true, 2, 1, "REVIEW");
        assertThatThrownBy(() -> IdentityAccessState.from(user).manual(true)).isInstanceOf(IdentityException.class);
        var result = service.restore("token", 7, confirmation(check()));
        assertThat(result.enabled()).isTrue();
        assertThat(result.outcome()).isEqualTo("RESTORED_RELOGIN_REQUIRED");
        assertThat(user.getAuthEpoch()).isEqualTo(3);
    }

    @Test
    void deniedPermissionPrecedesTargetProofReceiptAndProviderEvenForReplay() {
        var request = confirmation(check());
        service.restore("token", 7, request);
        org.mockito.Mockito.clearInvocations(users, mappings, inbox, member);
        doThrow(IdentityException.forbidden("权限撤销")).when(auth).require("token", Permission.IDENTITY_LIFECYCLE_RESTORE);
        assertThatThrownBy(this::check).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.restore("token", 7, request)).isInstanceOf(IdentityException.class);
        verifyNoInteractions(users, mappings, inbox, member);
    }

    @Test
    void expiredProofOrRoleRevisionChangedBackCannotRestore() {
        var request = confirmation(check());
        when(mappings.roleFactRevision(7)).thenReturn(3L);
        assertThatThrownBy(() -> service.restore("token", 7, request)).isInstanceOf(IdentityException.class);
        when(mappings.roleFactRevision(7)).thenReturn(1L);
        clock.now = clock.now.plusSeconds(300);
        assertThatThrownBy(() -> service.restore("token", 7, request)).isInstanceOf(IdentityException.class);
        verify(users, never()).setUserAccessState(anyLong(), anyBoolean(), anyBoolean(), anyLong(), anyLong());
    }

    @Test
    void newEventDuringSecondMemberCheckCannotBeOverwritten() {
        var request = confirmation(check());
        when(member.current("staff")).thenAnswer(call -> {
            boundary = new LifecycleEventBoundary(12, 2, 0, 0, 2);
            return currentEmployee();
        });
        assertThatThrownBy(() -> service.restore("token", 7, request)).isInstanceOf(IdentityException.class);
        assertThat(user.getAuthEpoch()).isEqualTo(2);
        assertThat(proofs.values()).allMatch(proof -> proof.consumedAt() == null);
    }

    @Test
    void conflictFlagAndPendingEventsInvalidateExistingCheck() {
        var request = confirmation(check());
        boundary = new LifecycleEventBoundary(11, 1, 1, 0, 1);
        assertThatThrownBy(() -> service.restore("token", 7, request)).isInstanceOf(IdentityException.class);
        boundary = new LifecycleEventBoundary(12, 2, 1, 1, 1);
        assertThatThrownBy(this::check).isInstanceOf(IdentityException.class);
        assertThat(receipts).isEmpty();
    }

    @Test
    void invalidDepartmentClearanceOrMemberFailsWithoutIssuingProof() {
        when(levels.contains((short) 1)).thenReturn(false);
        assertThatThrownBy(this::check).isInstanceOf(IdentityException.class);
        verifyNoInteractions(member);
        when(levels.contains((short) 1)).thenReturn(true);
        when(users.findDepartment(1)).thenReturn(new DepartmentRecord(1L, null, "D", "部门", "/1/", false, 2L, null, null));
        assertThatThrownBy(this::check).isInstanceOf(IdentityException.class);
        verifyNoInteractions(member);
        when(users.findDepartment(1)).thenReturn(new DepartmentRecord(1L, null, "D", "部门", "/1/", true, 2L, null, null));
        when(member.current("staff")).thenThrow(ApiException.unavailable("核验超时"));
        assertThatThrownBy(this::check).isInstanceOf(ApiException.class);
        doReturn(new CurrentEmployee("dingtalk", "ding-other", "staff", "internal", "员工", clock.instant()))
                .when(member).current("staff");
        assertThatThrownBy(this::check).isInstanceOf(ApiException.class);
        assertThat(proofs).isEmpty();
    }

    @Test
    void providerFailureBeforeConfirmationDoesNotConsumeProofOrRetryWrite() {
        var request = confirmation(check());
        when(member.current("staff")).thenThrow(ApiException.unavailable("超时"));
        assertThatThrownBy(() -> service.restore("token", 7, request)).isInstanceOf(ApiException.class);
        verify(member, times(2)).current("staff");
        assertThat(proofs.values()).allMatch(proof -> proof.consumedAt() == null);
        assertThat(receipts).isEmpty();
        assertThat(user.getAuthEpoch()).isEqualTo(2);
    }

    @Test
    void auditFailureRollsBackRestrictionEpochCleanupProofAndReceiptTogether() {
        var request = confirmation(check());
        doThrow(new IllegalStateException("审计失败")).when(audit)
                .successWithReason(any(), anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.restore("token", 7, request)).isInstanceOf(IllegalStateException.class);
        assertThat(user.getAuthEpoch()).isEqualTo(2);
        assertThat(mapping.restriction()).isEqualTo(LifecycleAssignment.Restriction.REVIEW);
        assertThat(cleanup).isZero();
        assertThat(resolved).isFalse();
        assertThat(receipts).isEmpty();
        assertThat(proofs.values()).allMatch(proof -> proof.consumedAt() == null);
    }

    @Test
    void targetScopeAndVersionChangesDoNotConsumeProof() {
        var request = confirmation(check());
        assertThatThrownBy(() -> service.restore("token", 9, request)).isInstanceOf(IdentityException.class);
        user = identity(false, 2, 2, "REVIEW");
        assertThatThrownBy(() -> service.restore("token", 7, request)).isInstanceOf(IdentityException.class);
        assertThat(proofs.values()).allMatch(proof -> proof.consumedAt() == null);
        assertThat(receipts).isEmpty();
    }

    private LifecycleRestorationService.Verified check() {
        return service.check("token", 7, new LifecycleRestorationService.Check(1, 1));
    }

    private LifecycleRestorationService.Confirmation confirmation(LifecycleRestorationService.Verified verified) {
        return new LifecycleRestorationService.Confirmation(1, 1, verified.proof(), "复职核对完成", "restore");
    }

    private CurrentEmployee currentEmployee() {
        return new CurrentEmployee("dingtalk", "ding-corp", "staff", "internal", "员工", clock.instant());
    }

    private static LifecycleProof consumed(LifecycleProof old, Instant now) {
        return new LifecycleProof(old.tokenHash(), old.actorUserId(), old.targetUserId(), old.operation(), old.bindingDigest(),
                old.evidence(), old.expiresAt(), now, old.createdAt());
    }

    private static IdentityRecord identity(boolean manual, long epoch, long version, String restriction) {
        return new IdentityRecord(7L, "local", "员工", 1L, (short) 1, manual && "CLEAR".equals(restriction), version,
                null, null, manual, epoch, restriction);
    }

    private static LifecycleAssignment assignment(long version, LifecycleAssignment.Restriction restriction, Instant restoredAt) {
        return new LifecycleAssignment(7, LifecycleAssignment.Kind.MANAGED, "dingtalk", "ding-corp", "staff", "internal",
                restriction, version, 11L, restoredAt, 8L, null, null);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-02-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final class MemoryTransactions extends AbstractPlatformTransactionManager {
        private IdentityRecord savedUser;
        private LifecycleAssignment savedMapping;
        private Map<String, LifecycleProof> savedProofs;
        private Map<String, LifecycleReceipt> savedReceipts;
        private int savedCleanup;
        private boolean savedResolved;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertThat(definition.getTimeout()).isEqualTo(5);
            savedUser = user;
            savedMapping = mapping;
            savedProofs = new HashMap<>(proofs);
            savedReceipts = new HashMap<>(receipts);
            savedCleanup = cleanup;
            savedResolved = resolved;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            user = savedUser;
            mapping = savedMapping;
            proofs = savedProofs;
            receipts = savedReceipts;
            cleanup = savedCleanup;
            resolved = savedResolved;
        }
    }
}
