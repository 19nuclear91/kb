package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.model.EmergencyIdentityFact;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LifecycleMappingServiceTest {
    private final IdentityDepartmentMapper users = mock(IdentityDepartmentMapper.class);
    private final LifecycleMapper mappings = mock(LifecycleMapper.class);
    private final LifecycleInboxMapper inbox = mock(LifecycleInboxMapper.class);
    private final LifecycleAuthorizer auth = mock(LifecycleAuthorizer.class);
    private final DingTalkMembership member = mock(DingTalkMembership.class);
    private final LifecycleReception reception = mock(LifecycleReception.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);
    private final Map<String, LifecycleProof> proofs = new HashMap<>();
    private final Map<String, LifecycleReceipt> receipts = new HashMap<>();
    private IdentityRecord user = new IdentityRecord(7L, "local", "本地员工", 1L, (short) 1, true, 0L, null, null);
    private LifecycleAssignment assignment;
    private final LifecycleMappingService service = new LifecycleMappingService(users, mappings, inbox, auth,
            DingTalkLifecycleSettingsTest.configured(false), member, new LifecycleProofService(mappings, clock),
            new LifecycleReceipts(mappings), reception, audit, manager);
    private final LifecycleMappingService.Verification input = new LifecycleMappingService.Verification("dingtalk", "internal", "staff", 0, -1);

    @BeforeEach
    void repository() {
        when(auth.require("token", Permission.IDENTITY_LIFECYCLE_MANAGE))
                .thenReturn(new EpochSessionService.Session(8, 1, EpochSessionService.Method.LOCAL, false));
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(users.findIdentity(7)).thenAnswer(call -> user);
        when(users.findIdentityForUpdate(7)).thenAnswer(call -> user);
        when(mappings.findAssignment(7)).thenAnswer(call -> assignment);
        when(mappings.lockAssignment(7)).thenAnswer(call -> assignment);
        when(member.current("staff")).thenReturn(new CurrentEmployee("dingtalk", "ding-corp", "staff", "internal", "员工", clock.instant()));
        when(mappings.insertProof(any())).thenAnswer(call -> {
            var proof = call.getArgument(0, LifecycleProof.class);
            proofs.put(proof.tokenHash(), proof);
            return 1;
        });
        when(mappings.lockProof(anyString())).thenAnswer(call -> proofs.get(call.getArgument(0)));
        when(mappings.consumeProof(anyString(), any())).thenAnswer(call -> {
            var old = proofs.get(call.getArgument(0));
            proofs.put(old.tokenHash(), new LifecycleProof(old.tokenHash(), old.actorUserId(), old.targetUserId(), old.operation(),
                    old.bindingDigest(), old.evidence(), old.expiresAt(), call.getArgument(1), old.createdAt()));
            return 1;
        });
        when(mappings.findReceipt(eq(8L), anyString())).thenAnswer(call -> receipts.get(call.getArgument(1)));
        when(mappings.insertReceipt(any())).thenAnswer(call -> {
            var receipt = call.getArgument(0, LifecycleReceipt.class);
            receipts.put(receipt.requestId(), receipt);
            return 1;
        });
        when(mappings.registerEmployee(eq(7L), anyLong(), anyString(), anyString(), anyString(), eq(8L)))
                .thenAnswer(call -> {
                    assignment = assignment(0, LifecycleAssignment.Restriction.CLEAR);
                    return 1;
                });
        when(mappings.appendHistory(eq(7L), eq(8L), anyString(), anyString())).thenReturn(1);
        when(users.findEmergencyIdentities()).thenReturn(List.of(
                new EmergencyIdentityFact(8, 1L, "EMERGENCY", true, true, true, true, false, "CLEAR")));
        when(users.findEmergencyPermissions()).thenReturn(ManagementInvariant.EMERGENCY_PERMISSIONS.stream()
                .map(code -> new UserRolePermission(8L, 1L, code)).toList());
    }

    @Test
    void localAccountMappingConsumesScopedProofAndExactReplayReturnsOriginalReceipt() {
        var verified = service.verify("token", 7, input);
        assertThat(verified.displayName()).isEqualTo("员工");
        assertThat(verified.expiresAt()).isEqualTo(clock.instant().plusSeconds(300));
        var confirmation = new LifecycleMappingService.Confirmation(input, verified.proof(), "确认纳管", "map-request");
        var result = service.map("token", 7, confirmation);
        assertThat(result.managed()).isTrue();
        assertThat(result.enabled()).isTrue();
        assertThat(service.map("token", 7, confirmation)).isEqualTo(result);
        assertThat(proofs.values().iterator().next().consumedAt()).isEqualTo(clock.instant());
        assertThat(receipts.get("map-request").response()).doesNotContain("staff", verified.proof());
        assertThatThrownBy(() -> service.map("token", 7,
                new LifecycleMappingService.Confirmation(input, verified.proof(), "改变原因", "map-request")))
                .isInstanceOf(IdentityException.class);
    }

    @Test
    void proofCannotChangeTargetEmployeeSourceOrBeReusedForAnotherVersion() {
        var verified = service.verify("token", 7, input);
        when(users.findIdentityForUpdate(9)).thenReturn(new IdentityRecord(9L, "another", "其他", 1L, (short) 1, true, 0L, null, null));
        assertThatThrownBy(() -> service.map("token", 9,
                new LifecycleMappingService.Confirmation(input, verified.proof(), "确认", "other-target"))).isInstanceOf(IdentityException.class);
        var differentEmployee = new LifecycleMappingService.Verification("dingtalk", "internal", "other", 0, -1);
        assertThatThrownBy(() -> service.map("token", 7,
                new LifecycleMappingService.Confirmation(differentEmployee, verified.proof(), "确认", "other-employee")))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.verify("token", 7,
                new LifecycleMappingService.Verification("wecom", "internal", "staff", 0, -1)))
                .isInstanceOf(IdentityException.class);
        service.map("token", 7, new LifecycleMappingService.Confirmation(input, verified.proof(), "确认", "one"));
        var later = new LifecycleMappingService.Verification("dingtalk", "internal", "staff", 0, 0);
        assertThatThrownBy(() -> service.map("token", 7,
                new LifecycleMappingService.Confirmation(later, verified.proof(), "确认", "two"))).isInstanceOf(IdentityException.class);
    }

    @Test
    void eventBeforeMappingIsAppliedBeforeReturningAndRestrictedMappingCannotExit() {
        var verified = service.verify("token", 7, input);
        when(inbox.unresolvedEmployeeEvents("ding-corp", "staff")).thenReturn(List.of(11L));
        when(inbox.findEventById(11)).thenReturn(new LifecycleEventRecord(11, "scope", "event", "user_leave_org",
                "ding-corp", "internal", clock.instant(), "digest", false, clock.instant()));
        when(reception.reprocessEmployeeWithinTransaction(11, "staff")).thenAnswer(call -> {
            assignment = assignment(1, LifecycleAssignment.Restriction.OFFBOARDED);
            user = new IdentityRecord(7L, "local", "本地员工", 1L, (short) 1, false, 1L, null, null,
                    true, 2L, "OFFBOARDED");
            return new LifecycleReception.Result(11, false, false, 1, 0, 0);
        });
        var result = service.map("token", 7, new LifecycleMappingService.Confirmation(input, verified.proof(), "确认纳管", "map"));
        assertThat(result.enabled()).isFalse();
        assertThat(result.restriction()).isEqualTo(LifecycleAssignment.Restriction.OFFBOARDED);
        assertThatThrownBy(() -> service.exit("token", 7, new LifecycleMappingService.Exit(1, 1, "退出", "exit")))
                .isInstanceOf(IdentityException.class);
        verify(mappings, never()).exitEmployee(anyLong(), anyLong());
    }

    @Test
    void permissionRevocationIsCheckedBeforeTargetProviderOrReceipt() {
        doThrow(IdentityException.forbidden("缺少权限")).when(auth).require("token", Permission.IDENTITY_LIFECYCLE_MANAGE);
        assertThatThrownBy(() -> service.verify("token", 7, input)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.map("token", 7, new LifecycleMappingService.Confirmation(input, "proof", "原因", "request")))
                .isInstanceOf(IdentityException.class);
        verifyNoInteractions(member, inbox);
        verify(users, never()).findIdentity(anyLong());
        verify(mappings, never()).findReceipt(anyLong(), anyString());
    }

    @Test
    void pendingRestorationBlocksExitWithoutPartialWrite() {
        assignment = assignment(0, LifecycleAssignment.Restriction.CLEAR);
        when(mappings.restorationInFlight(7)).thenReturn(true);
        assertThatThrownBy(() -> service.exit("token", 7, new LifecycleMappingService.Exit(0, 0, "退出", "exit")))
                .isInstanceOf(IdentityException.class);
        verify(mappings, never()).exitEmployee(anyLong(), anyLong());
        verifyNoInteractions(audit);
    }

    private static LifecycleAssignment assignment(long version, LifecycleAssignment.Restriction restriction) {
        return new LifecycleAssignment(7, LifecycleAssignment.Kind.MANAGED, "dingtalk", "ding-corp", "staff", "internal",
                restriction, version, null, null, 8L, null, null);
    }
}
