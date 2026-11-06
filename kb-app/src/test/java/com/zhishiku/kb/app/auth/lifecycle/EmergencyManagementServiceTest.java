package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.EmergencyIdentityFact;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.LoginThrottle;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

class EmergencyManagementServiceTest {
    private final IdentityDepartmentMapper users = mock(IdentityDepartmentMapper.class);
    private final LifecycleMapper mapper = mock(LifecycleMapper.class);
    private final LifecycleAuthorizer auth = mock(LifecycleAuthorizer.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final Map<String, LifecycleProof> proofs = new HashMap<>();
    private final Map<Long, LifecycleAssignment> assignments = new HashMap<>();
    private final Map<String, LifecycleReceipt> receipts = new HashMap<>();
    private EmergencyManagementService service;

    @BeforeEach
    void fixture() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new EmergencyManagementService(users, mapper, auth, new LifecycleProofService(mapper, clock),
                new LifecycleReceipts(mapper), audit, encoder, mock(LoginThrottle.class), manager);
        when(auth.require("local", Permission.IDENTITY_EMERGENCY_MANAGE))
                .thenReturn(new EpochSessionService.Session(7, 1, EpochSessionService.Method.LOCAL, false));
        var user = new IdentityRecord(7L, "candidate", "本人", 2L, (short) 1, true, 0L, null, null);
        when(users.findIdentity(7)).thenReturn(user);
        when(users.findIdentityForUpdate(7)).thenReturn(user);
        when(users.findDepartment(2)).thenReturn(new DepartmentRecord(2L, null, "d", "部门", "/2/", true, 0L, null, null));
        when(users.findPasswordHashById(7)).thenReturn(encoder.encode("correct-password"));
        var codes = ManagementInvariant.EMERGENCY_PERMISSIONS.stream().map(code -> new UserRolePermission(7L, 1L, code)).toList();
        when(users.findUserManagementPermissions(7)).thenReturn(codes);
        when(users.findEmergencyPermissions()).thenReturn(codes);
        when(users.findEmergencyCandidate(7)).thenReturn(new EmergencyIdentityFact(7, 2L, "NONE", true, true, true, true, false, "CLEAR"));
        when(users.findEmergencyIdentities()).thenReturn(List.of(new EmergencyIdentityFact(7, 2L, "EMERGENCY", true, true, true, true, false, "CLEAR")));
        when(users.lifecycleProtectionRequired()).thenReturn(true);
        when(mapper.findAssignment(anyLong())).thenAnswer(call -> assignments.get(call.getArgument(0, Long.class)));
        when(mapper.registerEmergency(7)).thenAnswer(call -> {
            assignments.put(7L, new LifecycleAssignment(7, LifecycleAssignment.Kind.EMERGENCY, null, null, null, null,
                    LifecycleAssignment.Restriction.CLEAR, 0, null, null, 7, null, null));
            return 1;
        });
        when(mapper.appendHistory(anyLong(), any(), anyString(), anyString())).thenReturn(1);
        when(mapper.insertProof(any())).thenAnswer(call -> {
            LifecycleProof proof = call.getArgument(0);
            proofs.put(proof.tokenHash(), proof);
            return 1;
        });
        when(mapper.lockProof(anyString())).thenAnswer(call -> proofs.get(call.getArgument(0, String.class)));
        when(mapper.consumeProof(anyString(), any())).thenAnswer(call -> {
            String hash = call.getArgument(0);
            var proof = proofs.get(hash);
            if (proof.consumedAt() != null) {
                return 0;
            }
            proofs.put(hash, new LifecycleProof(hash, proof.actorUserId(), proof.targetUserId(), proof.operation(),
                    proof.bindingDigest(), proof.evidence(), proof.expiresAt(), clock.instant(), proof.createdAt()));
            return 1;
        });
        when(mapper.findReceipt(eq(7L), anyString())).thenAnswer(call -> receipts.get(call.getArgument(1, String.class)));
        when(mapper.insertReceipt(any())).thenAnswer(call -> {
            LifecycleReceipt receipt = call.getArgument(0);
            receipts.put(receipt.requestId(), receipt);
            return 1;
        });
    }

    @Test
    void rotationRegistersVerifiedCandidateBeforeRetiringPreviousGuarantee() {
        var old = new IdentityRecord(9L, "old", "原应急账号", 2L, (short) 1, true, 0L, null, null);
        when(users.findIdentityForUpdate(9)).thenReturn(old);
        assignments.put(9L, new LifecycleAssignment(9, LifecycleAssignment.Kind.EMERGENCY, null, null, null, null,
                LifecycleAssignment.Restriction.CLEAR, 4, null, null, 9, null, null));
        when(mapper.retireEmergency(9, 4)).thenReturn(1);
        var verified = service.verify("local", new EmergencyManagementService.Verification(7, 0, 9L, 4L, "correct-password"));
        var result = service.register("local", new EmergencyManagementService.Confirmation(7, 0, -1, 9L, 4L,
                verified.proof(), "轮换应急保障", "rotation-1"));
        assertThat(result.replacedUserId()).isEqualTo(9);
        var order = inOrder(mapper);
        order.verify(mapper).registerEmergency(7);
        order.verify(mapper).retireEmergency(9, 4);
    }

    @Test
    void verifiedRegistrationReplaysWithoutConsumingProofAgainAndStoresNoRawSecret() {
        var verified = service.verify("local", new EmergencyManagementService.Verification(7, 0, null, null, "correct-password"));
        var input = new EmergencyManagementService.Confirmation(7, 0, -1, null, null, verified.proof(), "建立应急保障", "request-1");
        var result = service.register("local", input);
        assertThat(result.registered()).isTrue();
        assertThat(service.register("local", input)).isEqualTo(result);
        verify(mapper, times(1)).registerEmergency(7);
        verify(mapper, times(1)).consumeProof(anyString(), any());
        assertThat(LifecycleSupport.json(proofs)).doesNotContain("correct-password", verified.proof());
        assertThat(LifecycleSupport.json(receipts)).doesNotContain("correct-password", verified.proof());
        assertThat(verified.toString()).doesNotContain(verified.proof());
        assertThat(input.toString()).doesNotContain(verified.proof());
    }

    @Test
    void developerSsoAndAnotherUserCannotVerifyCandidatePassword() {
        for (var method : new EpochSessionService.Method[]{EpochSessionService.Method.DEV, EpochSessionService.Method.SSO}) {
            when(auth.require("local", Permission.IDENTITY_EMERGENCY_MANAGE))
                    .thenReturn(new EpochSessionService.Session(7, 1, method, false));
            assertThatThrownBy(() -> service.verify("local", new EmergencyManagementService.Verification(7, 0, null, null, "correct-password")))
                    .isInstanceOf(IdentityException.class);
        }
        when(auth.require("local", Permission.IDENTITY_EMERGENCY_MANAGE))
                .thenReturn(new EpochSessionService.Session(8, 1, EpochSessionService.Method.LOCAL, false));
        assertThatThrownBy(() -> service.verify("local", new EmergencyManagementService.Verification(7, 0, null, null, "correct-password")))
                .isInstanceOf(IdentityException.class);
        verifyNoInteractions(users, mapper);
    }

    @Test
    void changedPasswordOrPermissionsAfterVerificationInvalidateProof() {
        var verified = service.verify("local", new EmergencyManagementService.Verification(7, 0, null, null, "correct-password"));
        when(users.findPasswordHashById(7)).thenReturn(encoder.encode("another-password"));
        assertThatThrownBy(() -> service.register("local", new EmergencyManagementService.Confirmation(7, 0, -1,
                null, null, verified.proof(), "建立应急保障", "request-1"))).isInstanceOf(IdentityException.class);
        verify(mapper, never()).registerEmergency(anyLong());
    }
}
