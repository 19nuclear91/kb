package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static com.zhishiku.kb.app.auth.lifecycle.LifecycleAssignment.Restriction.CLEAR;
import static com.zhishiku.kb.app.auth.lifecycle.LifecycleAssignment.Restriction.OFFBOARDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifecycleStateServiceTest {
    @Test
    void revocationEvidenceBindsOriginalEpochAndFailureRollsBackTheBarrier() {
        var evidence = mock(com.zhishiku.kb.app.auth.mapper.OutputStopEvidenceMapper.class);
        service.setEvidence(evidence);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.restrict(7, 0, 11, OFFBOARDED, "event-11", true)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(user.getAuthEpoch()).isEqualTo(1);
        assertThat(assignment.restriction()).isEqualTo(CLEAR);
        when(evidence.revoke(7, 1, 11, true)).thenReturn(1);
        transactions.executeWithoutResult(status -> service.restrict(7, 0, 11, OFFBOARDED, "event-11", true));
        transactions.executeWithoutResult(status -> service.restrict(7, 1, 11, OFFBOARDED, "event-11", true));
        verify(evidence, org.mockito.Mockito.times(2)).revoke(7, 1, 11, true);
    }
    private final IdentityDepartmentMapper users = mock(IdentityDepartmentMapper.class);
    private final LifecycleMapper lifecycle = mock(LifecycleMapper.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final LifecycleStateService service = new LifecycleStateService(users, lifecycle, audit);
    private IdentityRecord user = user(false, CLEAR, 1, 0);
    private LifecycleAssignment assignment = assignment(CLEAR, 0, null);
    private int histories;
    private int cleanups;
    private final TransactionTemplate transactions = new TransactionTemplate(new MemoryTransactionManager());

    @BeforeEach
    void repository() {
        when(users.findEmergencyIdentities()).thenReturn(java.util.List.of(
                new com.zhishiku.kb.app.auth.model.EmergencyIdentityFact(8, 1L, "EMERGENCY", true, true, true, true, false, "CLEAR")));
        var permissions = com.zhishiku.kb.app.auth.service.ManagementInvariant.EMERGENCY_PERMISSIONS.stream()
                .map(code -> new com.zhishiku.kb.app.auth.model.UserRolePermission(8L, 1L, code)).toList();
        when(users.findEmergencyPermissions()).thenReturn(permissions);
        when(users.findEnabledManagementPermissions()).thenReturn(permissions);
        when(users.findIdentityForUpdate(7)).thenAnswer(call -> user);
        when(users.findIdentity(7)).thenAnswer(call -> user);
        when(lifecycle.lockAssignment(7)).thenAnswer(call -> assignment);
        when(lifecycle.updateRestriction(eq(7L), anyLong(), any(), any(), any())).thenAnswer(call -> {
            assignment = assignment(call.getArgument(2), assignment.version() + 1, call.getArgument(3));
            return 1;
        });
        when(users.setUserAccessState(eq(7L), anyBoolean(), anyBoolean(), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    user = user(call.getArgument(1), assignment.restriction(), call.getArgument(3),
                            user.getVersion() + 1);
                    assertThat(user.getEnabled()).isEqualTo(call.getArgument(2, Boolean.class));
                    return 1;
                });
        when(users.enqueueSessionCleanup(eq(7L), anyLong())).thenAnswer(call -> ++cleanups);
        when(lifecycle.appendHistory(eq(7L), any(), anyString(), anyString())).thenAnswer(call -> {
            histories++;
            return 1;
        });
    }

    @Test
    void firstRestrictionAdvancesEpochOfManuallyDisabledUserAndReplayDoesNotWrite() {
        transactions.executeWithoutResult(status -> service.restrict(7, 0, 11, OFFBOARDED, "event-11"));
        assertThat(user.getAuthEpoch()).isEqualTo(2);
        assertThat(user.getEnabled()).isFalse();
        transactions.executeWithoutResult(status -> service.restrict(7, 1, 11, OFFBOARDED, "event-11"));
        assertThat(histories).isEqualTo(1);
        assertThat(cleanups).isEqualTo(1);
        var order = inOrder(users, lifecycle);
        order.verify(users).lockManagement();
        order.verify(lifecycle).lockAssignment(7);
        order.verify(users).findIdentityForUpdate(7);
    }

    @Test
    void restorationDoesNotUndoManualDisableAndCannotResurrectEpoch() {
        transactions.executeWithoutResult(status -> service.restrict(7, 0, 11, OFFBOARDED, "event-11"));
        transactions.executeWithoutResult(status -> service.restore(7, 1, Instant.now(), 8, "restore", "确认复职"));
        assertThat(user.getAuthEpoch()).isEqualTo(3);
        assertThat(user.getManualEnabled()).isFalse();
        assertThat(user.getEnabled()).isFalse();
        assertThat(assignment.restriction()).isEqualTo(CLEAR);
    }

    @Test
    void auditFailureRollsBackUserRestrictionHistoryAndCleanupInSameTransaction() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .successWithReason(any(), anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                status -> service.restrict(7, 0, 11, OFFBOARDED, "event-11")))
                .hasMessage("audit unavailable");
        assertThat(user.getAuthEpoch()).isEqualTo(1);
        assertThat(user.getVersion()).isZero();
        assertThat(assignment.restriction()).isEqualTo(CLEAR);
        assertThat(histories).isZero();
        assertThat(cleanups).isZero();
    }

    @Test
    void ordinaryEnableRejectsRestrictionBeforeReplayAndMakesNoWrites() {
        user = user(true, OFFBOARDED, 2, 1);
        IdentityService identity = new IdentityService(users, null, audit);
        assertThatThrownBy(() -> identity.setEnabled(7, true, 1, 8L, "old-enable"))
                .isInstanceOfSatisfying(IdentityException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(409));
        verify(audit, never()).existing(any(), anyString());
        verify(users, never()).setUserAccessState(anyLong(), anyBoolean(), anyBoolean(), anyLong(), anyLong());
    }

    private static IdentityRecord user(boolean manual, LifecycleAssignment.Restriction restriction,
                                       long epoch, long version) {
        return new IdentityRecord(7L, "local", "本地员工", 1L, (short) 1,
                manual && restriction == CLEAR, version, null, null, manual, epoch, restriction.name());
    }

    private static LifecycleAssignment assignment(LifecycleAssignment.Restriction restriction,
                                                   long version, Long eventId) {
        return new LifecycleAssignment(7, LifecycleAssignment.Kind.MANAGED, "DINGTALK", "corp", "employee",
                "connection", restriction, version, eventId, null, 8, null, null);
    }

    /** 只模拟提交/回滚，不启动数据库，也不宣称证明数据库并发行为。 */
    private final class MemoryTransactionManager extends AbstractPlatformTransactionManager {
        private IdentityRecord savedUser;
        private LifecycleAssignment savedAssignment;
        private int savedHistories;
        private int savedCleanups;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            savedUser = user;
            savedAssignment = assignment;
            savedHistories = histories;
            savedCleanups = cleanups;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // 内存写入已经可见，提交无需外部组件。
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            user = savedUser;
            assignment = savedAssignment;
            histories = savedHistories;
            cleanups = savedCleanups;
        }
    }
}
