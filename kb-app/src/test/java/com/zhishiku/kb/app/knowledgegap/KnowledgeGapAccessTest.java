package com.zhishiku.kb.app.knowledgegap;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapCategory;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapSource;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 当前身份、角色和仓储均为替身，不启动应用或会话存储。 */
class KnowledgeGapAccessTest {
    private final IdentityService identity = mock(IdentityService.class);
    private final IdentityDepartmentMapper identities = mock(IdentityDepartmentMapper.class);
    private final RolePermissionMapper roles = mock(RolePermissionMapper.class);
    private final KnowledgeGapMapper gaps = mock(KnowledgeGapMapper.class);
    private final PermissionService permissions = new PermissionService(mock(PermissionMapper.class), identity, roles);
    private final KnowledgeGapAccess access = new KnowledgeGapAccess(new AdminQueryAccess(permissions, identities), gaps);

    @BeforeEach
    void setUp() {
        when(identity.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 2L, (short) 1,
                List.of(3L, 4L), List.of(2L)));
        when(identities.findMustChangePasswordById(7)).thenReturn(false);
    }

    @Test
    void currentReporterHandlerAndReaderCanReadButOldAssignmentCannot() {
        when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:handle"));
        when(gaps.findVisible(11, 7)).thenReturn(gap(8, 7L, KnowledgeGapState.IN_PROGRESS));
        assertThat(access.requireVisible(permissions.principalOf(7), 11).id()).isEqualTo(11);
        when(gaps.findVisible(11, 7)).thenReturn(gap(8, 9L, KnowledgeGapState.IN_PROGRESS));
        assertStatus(404, () -> access.requireVisible(permissions.principalOf(7), 11));
        when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:read"));
        assertThat(access.requireVisible(permissions.principalOf(7), 11).assigneeUserId()).isEqualTo(9);
        when(roles.findUserPermissions(7)).thenReturn(List.of());
        when(gaps.findVisible(11, 7)).thenReturn(gap(7, 9L, KnowledgeGapState.IN_PROGRESS));
        assertThat(access.requireVisible(permissions.principalOf(7), 11).reporterUserId()).isEqualTo(7);
    }

    @Test
    void actionPermissionsAreIndependentAndCheckedBeforeTargetReads() {
        when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:read"));
        assertStatus(403, () -> access.requireForAction(permissions.principalOf(7), 11, KnowledgeGapAction.ASSIGN));
        assertStatus(403, () -> access.requireForAction(permissions.principalOf(7), 11, KnowledgeGapAction.CLOSE));
        assertStatus(403, () -> access.requireForAction(permissions.principalOf(7), 11, KnowledgeGapAction.SOLUTION));
        verifyNoInteractions(gaps);
        when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:assign", "knowledge:gap:close"));
        when(gaps.findVisible(11, 7)).thenReturn(gap(8, 9L, KnowledgeGapState.IN_PROGRESS));
        assertStatus(404, () -> access.requireForAction(permissions.principalOf(7), 11, KnowledgeGapAction.ASSIGN));
        assertStatus(404, () -> access.requireForAction(permissions.principalOf(7), 11, KnowledgeGapAction.CLOSE));
    }

    @Test
    void roleNamesWildcardsAndOtherManagementCodesDoNotAuthorize() {
        for (String code : List.of("ROLE_ADMIN", "space:manage-all", "identity:user:read", "knowledge:gap:*",
                "knowledge:gap:handle:extra", "knowledge:gap:assign")) {
            when(roles.findUserPermissions(7)).thenReturn(List.of(code));
            when(gaps.findVisible(11, 7)).thenReturn(gap(8, 7L, KnowledgeGapState.IN_PROGRESS));
            assertStatus(404, () -> access.requireVisible(permissions.principalOf(7), 11));
        }
    }

    @Test
    void onlyReporterConfirmsAndReadQualificationDoesNotSubstituteHandling() {
        var awaiting = gap(8, 7L, KnowledgeGapState.AWAITING_CONFIRMATION);
        when(roles.findUserPermissions(7)).thenReturn(Permission.codes());
        assertStatus(403, () -> access.requireAction(permissions.principalOf(7), awaiting, KnowledgeGapAction.CONFIRM));
        var own = gap(7, 7L, KnowledgeGapState.AWAITING_CONFIRMATION);
        access.requireAction(permissions.principalOf(7), own, KnowledgeGapAction.CONFIRM);
        when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:read"));
        assertStatus(403, () -> access.requireAction(permissions.principalOf(7), own, KnowledgeGapAction.SOLUTION));
        access.requireAction(permissions.principalOf(7), own, KnowledgeGapAction.CONFIRM);
    }

    @Test
    void replayQualificationIsCurrentButDoesNotRecheckTerminalState() {
        when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:handle"));
        var terminal = gap(8, 7L, KnowledgeGapState.RESOLVED);
        access.requireAction(permissions.principalOf(7), terminal, KnowledgeGapAction.SOLUTION);
        var capabilities = access.capabilities(permissions.principalOf(7), terminal);
        assertThat(capabilities.submitSolution()).isFalse();
        assertThat(capabilities.note()).isFalse();
        when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:read"));
        assertStatus(403, () -> access.requireAction(permissions.principalOf(7), terminal, KnowledgeGapAction.SOLUTION));
    }

    @Test
    void revocationAndReassignmentUseNewRequestWithoutHistoricalAccess() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:handle", "knowledge:gap:handle"));
            when(gaps.findVisible(11, 7)).thenReturn(gap(8, 7L, KnowledgeGapState.IN_PROGRESS));
            access.requireForAction(access.current(), 11, KnowledgeGapAction.SOLUTION);
            when(roles.findUserPermissions(7)).thenReturn(List.of());
            assertStatus(403, () -> access.requireForAction(access.current(), 11, KnowledgeGapAction.SOLUTION));
            assertStatus(404, () -> access.requireVisible(access.current(), 11));
            when(roles.findUserPermissions(7)).thenReturn(List.of("knowledge:gap:handle"));
            when(gaps.findVisible(11, 7)).thenReturn(gap(8, 9L, KnowledgeGapState.IN_PROGRESS));
            assertStatus(404, () -> access.requireVisible(access.current(), 11));
        }
    }

    @Test
    void identityAndPermissionFailuresRemainSafeErrors() {
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(identity.resolvePrincipal(7)).thenThrow(IdentityException.disabled("主体或部门停用"));
            assertStatus(403, access::current);
            doReturn(new ResolvedPrincipal(7, 2L, (short) 1, List.of(), List.of(2L))).when(identity).resolvePrincipal(7);
            when(identities.findMustChangePasswordById(7)).thenReturn(true);
            assertThatThrownBy(access::current).isInstanceOfSatisfying(QueryException.class,
                    failure -> assertThat(failure.status()).isEqualTo(403));
            when(identities.findMustChangePasswordById(7)).thenReturn(false);
            when(roles.findUserPermissions(7)).thenThrow(new DataAccessResourceFailureException("secret-jdbc"));
            assertThatThrownBy(() -> access.requireCurrent(Permission.KNOWLEDGE_GAP_READ))
                    .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.httpStatus()).isEqualTo(503))
                    .hasMessage("知识问题单访问暂不可用");
            login.when(StpUtil::checkLogin).thenThrow(new SaTokenException("secret-token"));
            assertStatus(401, access::current);
            verifyNoInteractions(gaps);
        }
    }

    private static KnowledgeGapRows.Gap gap(long reporter, Long assignee, KnowledgeGapState state) {
        return new KnowledgeGapRows.Gap(11, reporter, KnowledgeGapCategory.OTHER, KnowledgeGapSource.MANUAL,
                "共享问题", "GAP_SHARE_V1", state, 2, assignee, 1,
                state == KnowledgeGapState.AWAITING_CONFIRMATION ? UUID.randomUUID() : null,
                null, Instant.EPOCH, Instant.EPOCH);
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
