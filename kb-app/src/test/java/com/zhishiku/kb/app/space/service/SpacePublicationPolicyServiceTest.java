package com.zhishiku.kb.app.space.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.collaboration.mapper.CommandReceiptMapper;
import com.zhishiku.kb.app.collaboration.model.CommandReceiptRow;
import com.zhishiku.kb.app.collaboration.service.CommandReceiptService;
import com.zhishiku.kb.app.space.mapper.SpacePublicationPolicyMapper;
import com.zhishiku.kb.app.space.model.SpacePublicationPolicy;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpacePublicationPolicyServiceTest {
    private final IdentityManagementAuthorizer identityAuthorizer = mock(IdentityManagementAuthorizer.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final PermissionMapper permissionMapper = mock(PermissionMapper.class);
    private final PermissionService permissions = new PermissionService(permissionMapper, identities);
    private final SpaceAuthorizer authorizer = new SpaceAuthorizer(identityAuthorizer, identities, permissions);
    private final SpacePublicationPolicyMapper spaces = mock(SpacePublicationPolicyMapper.class);
    private final CommandReceiptMapper commands = mock(CommandReceiptMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CommandReceiptService receipts = new CommandReceiptService(commands, json);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final SpacePublicationPolicyService service = new SpacePublicationPolicyService(spaces, authorizer, receipts, audit);

    private void allow(boolean global) {
        when(identityAuthorizer.requireLogin()).thenReturn(7L);
        when(identities.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 5L, (short) 2, List.of(), List.of(5L)));
        when(identityAuthorizer.has(any(PermissionService.PrincipalHandle.class), eq(Permission.SPACE_MANAGE_ALL)))
                .thenReturn(global);
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq(PermissionService.PERM_MANAGE))).thenReturn(List.of(3L));
    }

    private void prepareWrite() {
        when(commands.claim(any())).thenReturn(1);
        when(commands.complete(any(), any())).thenReturn(1);
        when(spaces.lock(3)).thenReturn(new SpacePublicationPolicy(3, PublicationPolicy.AUTO, 8));
        when(spaces.find(3)).thenReturn(new SpacePublicationPolicy(3, PublicationPolicy.AUTO, 8));
        when(spaces.update(3, PublicationPolicy.REVIEW_REQUIRED, 8)).thenReturn(1);
    }

    @Test
    void delegatedManageAndGlobalManageUseSamePolicyBoundaryWithoutViewOrReview() {
        for (boolean global : List.of(false, true)) {
            allow(global);
            var state = new SpacePublicationPolicy(3, PublicationPolicy.AUTO, 8);
            when(spaces.find(3)).thenReturn(state);
            assertThat(service.get(3)).isEqualTo(state);
        }
        verify(permissionMapper, never()).findVisibleSpaceIds(anyList(), eq(PermissionService.PERM_VIEW));
        verify(permissionMapper, never()).findVisibleSpaceIds(anyList(), eq("review"));
        verifyNoInteractions(commands, audit);
    }

    @Test
    void stateAuditAndReceiptShareTransactionAndFailuresCannotReturnSuccess() throws Exception {
        allow(false);
        prepareWrite();
        var after = service.update(3, PublicationPolicy.REVIEW_REQUIRED, 8, "policy-1");
        assertThat(after).isEqualTo(new SpacePublicationPolicy(3, PublicationPolicy.REVIEW_REQUIRED, 9));
        var order = inOrder(commands, spaces, audit);
        order.verify(commands).claim(any());
        order.verify(spaces).lock(3);
        order.verify(spaces).update(3, PublicationPolicy.REVIEW_REQUIRED, 8);
        order.verify(audit).successWithReason(eq(AuditScope.SPACE_PUBLICATION_POLICY), eq("policy-1"), eq(7L), eq(3L),
                eq("publication-policy"), any(), any(), eq("POLICY_CHANGED"));
        order.verify(commands).complete(any(), any());
        assertThat(SpacePublicationPolicyService.class.getMethod("update", long.class, PublicationPolicy.class,
                long.class, String.class).getAnnotation(Transactional.class)).isNotNull();
        assertThat(CommandReceiptService.class.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void auditFailureDoesNotAcknowledgeOrFinalizeReceipt() {
        allow(false);
        prepareWrite();
        doThrow(new IllegalStateException("unavailable")).when(audit).successWithReason(any(), any(), any(), any(),
                any(), any(), any(), any());
        assertThatThrownBy(() -> service.update(3, PublicationPolicy.REVIEW_REQUIRED, 8, "failed"))
                .isInstanceOf(IllegalStateException.class);
        verify(commands, never()).complete(any(), any());
        // 只检查调用及事务配置，不据此断言 PostgreSQL 已实际回滚。
    }

    @Test
    void staleVersionDoesNotWritePolicyOrSuccessAudit() {
        allow(false);
        prepareWrite();
        assertThatThrownBy(() -> service.update(3, PublicationPolicy.REVIEW_REQUIRED, 7, "stale"))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).httpStatus()).isEqualTo(409);
        verify(spaces, never()).update(eq(3L), any(), eq(7L));
        verifyNoInteractions(audit);
        verify(commands, never()).complete(any(), any());
    }

    @Test
    void matchingReplayReturnsOriginalReceiptBeforeCheckingNowStaleExpectedVersionButReauthorizes() throws Exception {
        allow(false);
        prepareWrite();
        var stored = new AtomicReference<CommandReceiptRow>();
        when(commands.claim(any())).thenAnswer(call -> stored.get() == null ? 1 : 0);
        when(commands.find(any())).thenAnswer(call -> stored.get());
        doAnswer(call -> {
            ReviewCommandBinding binding = call.getArgument(0);
            var row = new CommandReceiptRow();
            row.setOperationDomain(binding.domain());
            row.setRequestId(binding.requestId());
            row.setActorUserId(binding.actorUserId());
            row.setSpaceId(binding.spaceId());
            row.setExpectedVersion(binding.expectedVersion());
            row.setInputDigest(binding.inputDigest());
            row.setReceipt(call.getArgument(1));
            stored.set(row);
            return 1;
        }).when(commands).complete(any(), any());
        var first = service.update(3, PublicationPolicy.REVIEW_REQUIRED, 8, "same");
        when(spaces.lock(3)).thenReturn(new SpacePublicationPolicy(3, PublicationPolicy.AUTO, 100));
        assertThat(service.update(3, PublicationPolicy.REVIEW_REQUIRED, 8, "same")).isEqualTo(first);
        verify(spaces, times(1)).lock(3);
        assertThatThrownBy(() -> service.update(3, PublicationPolicy.AUTO, 8, "same"))
                .isInstanceOf(ApiException.class).hasMessage("STATE_CONFLICT");
        when(identities.resolvePrincipal(7)).thenThrow(IdentityException.disabled("disabled"));
        assertThatThrownBy(() -> service.update(3, PublicationPolicy.REVIEW_REQUIRED, 8, "same"))
                .isInstanceOf(IdentityException.class);
        verify(commands, times(3)).claim(any());
    }

    @Test
    void noManageIsRejectedBeforeReceiptOrPolicyReads() {
        allow(false);
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq(PermissionService.PERM_MANAGE))).thenReturn(List.of());
        assertThatThrownBy(() -> service.get(3)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.update(3, PublicationPolicy.AUTO, 8, "denied"))
                .isInstanceOf(IdentityException.class);
        verifyNoInteractions(spaces, commands, audit);
    }
}
