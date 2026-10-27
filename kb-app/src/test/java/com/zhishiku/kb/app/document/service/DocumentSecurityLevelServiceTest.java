package com.zhishiku.kb.app.document.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityAuditMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.app.document.mapper.DocumentSecurityLevelMapper;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelChange;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelResult;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelState;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyShort;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 只验证业务分支和交互，不将模拟状态作为数据库锁或回滚证明。
 */
class DocumentSecurityLevelServiceTest {
    private final DocumentSecurityLevelMapper mapper = mock(DocumentSecurityLevelMapper.class);
    private final RolePermissionMapper permissions = mock(RolePermissionMapper.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final SqlSessionTemplate sessions = mock(SqlSessionTemplate.class);
    private final SecurityLevelDictionary levels = mock(SecurityLevelDictionary.class);
    private final IdentityAuditMapper events = mock(IdentityAuditMapper.class);
    private final Map<String, IdentityAuditRecord> history = new HashMap<>();
    private final AtomicReference<DocumentSecurityLevelState> state =
            new AtomicReference<>(new DocumentSecurityLevelState(7, (short) 1, 0));
    private final DocumentSecurityLevelService service = new DocumentSecurityLevelService(mapper,
            new IdentityManagementAuthorizer(permissions), identities, sessions, levels,
            new IdentityAuditService(events), new ObjectMapper());
    private MockedStatic<StpUtil> login;

    @BeforeEach
    void setUp() {
        login = mockStatic(StpUtil.class);
        login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
        when(permissions.findUserPermissions(anyLong())).thenReturn(List.of("document:security-level:write"));
        when(identities.resolvePrincipal(anyLong())).thenAnswer(call -> principal(call.getArgument(0), 3));
        when(levels.contains(any())).thenReturn(true);
        when(mapper.find(7)).thenAnswer(call -> state.get());
        when(mapper.lockDocument(7)).thenAnswer(call -> state.get());
        when(mapper.updateLevel(eq(7L), anyShort(), anyLong())).thenAnswer(call -> {
            var old = state.get();
            state.set(new DocumentSecurityLevelState(7, call.getArgument(1), old.securityVersion() + 1));
            return 1;
        });
        when(events.findByRequest(anyString(), anyString())).thenAnswer(call -> history.get(call.getArgument(1)));
        when(events.insert(any(), anyString(), anyString(), anyLong(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            var event = new IdentityAuditRecord(1L, call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6),
                    call.getArgument(7), call.getArgument(8), call.getArgument(9), call.getArgument(10),
                    call.getArgument(11));
            history.put(event.getRequestId(), event);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        login.close();
    }

    @Test
    void queryNeedsNoContentAclAndReturnsOnlyManagementState() {
        assertThat(service.get(7)).isEqualTo(state.get());
        verify(permissions).findUserPermissions(1L);
        verify(identities).resolvePrincipal(1L);
        verify(mapper).find(7);
        verifyNoInteractions(events);
    }

    @Test
    void queryRejectsMissingDocumentAndInsufficientClearance() {
        when(identities.resolvePrincipal(1)).thenReturn(principal(1, 0));
        rejects(() -> service.get(7), 403);
        when(mapper.find(7)).thenReturn(null);
        rejects(() -> service.get(7), 404);
        rejects(() -> service.get(0), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"用户已停用", "用户主部门已停用"})
    void disabledIdentityCannotQueryOrChange(String message) {
        when(identities.resolvePrincipal(1)).thenThrow(IdentityException.disabled(message));
        rejects(() -> service.get(7), 403);
        rejects(() -> change(2, 0, "r"), 403);
        verifyNoInteractions(mapper, events);
    }

    @Test
    void permissionRevocationIsEffectiveOnNextRequest() {
        assertThat(service.get(7)).isNotNull();
        when(permissions.findUserPermissions(1)).thenReturn(List.of("identity:clearance:write", "space:manage-all"));
        rejects(() -> service.get(7), 403);
        rejects(() -> change(2, 0, "r"), 403);
        noWrites();
    }

    @Test
    void raiseAndLowerProduceExactAuditAndVersionWithoutContentChanges() throws Exception {
        var first = change(3, 0, "raise");
        assertThat(first).isEqualTo(new DocumentSecurityLevelResult(7, (short) 1, (short) 3, 0, 1,
                "raise", first.changedAt(), false));
        assertThat(first.changedAt().getNano() % 1000).isZero();
        var lower = change(0, 1, "lower");
        assertThat(lower.securityVersion()).isEqualTo(2);
        assertThat(lower.previousSecurityLevel()).isEqualTo((short) 3);
        assertThat(state.get().securityLevel()).isZero();
        var event = history.get("raise");
        assertThat(event.getActorUserId()).isEqualTo(1);
        assertThat(event.getTargetId()).isEqualTo(7);
        assertThat(event.getTargetType()).isEqualTo("document");
        assertThat(event.getOperationScope()).isEqualTo("DOCUMENT_SECURITY_LEVEL_CHANGE");
        assertThat(event.getReason()).isEqualTo("定级复核");
        assertThat(new ObjectMapper().readTree(event.getBeforeState()).get("securityLevel").shortValue()).isEqualTo((short) 1);
        assertThat(new ObjectMapper().readTree(event.getAfterState()).get("expectedSecurityVersion").longValue()).isZero();
        verify(mapper).syncChunks(7, (short) 3);
        verify(mapper).syncChunks(7, (short) 0);
    }

    @Test
    void bothOriginalAndTargetMustFitCurrentClearance() {
        when(identities.resolvePrincipal(1)).thenReturn(principal(1, 1));
        rejects(() -> change(2, 0, "high-target"), 403);
        state.set(new DocumentSecurityLevelState(7, (short) 2, 0));
        rejects(() -> change(1, 0, "high-original"), 403);
        noWrites();
    }

    @Test
    void unknownDictionaryValueIsBadRequestEvenWhenAboveClearance() {
        when(levels.contains((short) 99)).thenReturn(false);
        rejects(() -> change(99, 0, "invalid"), 400);
        noWrites();
    }

    @Test
    void dictionaryCanIntroduceLevelsBeyondInitialSet() {
        when(identities.resolvePrincipal(1)).thenReturn(principal(1, 9));
        assertThat(change(9, 0, "new-level").securityLevel()).isEqualTo((short) 9);
        verify(levels).contains((short) 9);
    }

    @Test
    void oldVersionsAndNoChangeAreConflictsWithoutWrites() {
        assertThatThrownBy(() -> change(2, 9, "stale")).isInstanceOfSatisfying(IdentityException.class, error -> {
            assertThat(error.httpStatus()).isEqualTo(409);
            assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.INVALID_VERSION);
            assertThat(error.getMessage()).isEqualTo("密级版本已变化，请重新查询后提交");
        });
        assertThatThrownBy(() -> change(1, 0, "same")).isInstanceOfSatisfying(IdentityException.class, error -> {
            assertThat(error.httpStatus()).isEqualTo(409);
            assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
            assertThat(error.reason()).isEmpty();
        });
        noWrites();
    }

    @Test
    void returningToOriginalLevelDoesNotReviveOldVersion() {
        change(2, 0, "one");
        change(1, 1, "two");
        rejects(() -> change(3, 0, "old"), 409);
        assertThat(state.get().securityVersion()).isEqualTo(2);
        assertThat(history).hasSize(2);
    }

    @Test
    void replayRestoresOriginalResultDespiteLaterChangesAndNormalizesReason() {
        var original = change(2, 0, "one");
        change(3, 1, "two");
        var replay = service.change(7, new DocumentSecurityLevelChange((short) 2, 0L, "  定级复核  ", " one "), "one");
        assertThat(replay).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(original);
        assertThat(replay.replayed()).isTrue();
        assertThat(state.get().securityLevel()).isEqualTo((short) 3);
        assertThat(history).hasSize(2);
        verify(mapper, times(2)).updateLevel(anyLong(), anyShort(), anyLong());
    }

    @Test
    void reusedRequestCannotChangeActorDocumentTargetVersionOrReason() {
        change(2, 0, "one");
        rejects(() -> change(3, 0, "one"), 409);
        rejects(() -> change(2, 1, "one"), 409);
        rejects(() -> service.change(7, new DocumentSecurityLevelChange((short) 2, 0L, "不同原因", "one"), null), 409);
        rejects(() -> service.change(8, command(2, 0, "one"), null), 409);
        login.when(StpUtil::getLoginIdAsLong).thenReturn(2L);
        rejects(() -> change(2, 0, "one"), 409);
        assertThat(history).hasSize(1);
        verify(mapper, times(1)).updateLevel(anyLong(), anyShort(), anyLong());
    }

    @Test
    void replayRequiresCurrentAndHistoricalClearanceAndExistingDocument() {
        state.set(new DocumentSecurityLevelState(7, (short) 3, 0));
        change(1, 0, "lower");
        when(identities.resolvePrincipal(1)).thenReturn(principal(1, 1));
        rejects(() -> change(1, 0, "lower"), 403);
        when(identities.resolvePrincipal(1)).thenReturn(principal(1, 3));
        when(mapper.lockDocument(7)).thenReturn(null);
        rejects(() -> change(1, 0, "lower"), 404);
        assertThat(history).hasSize(1);
    }

    @Test
    void rechecksIdentityAfterWaitingForDocumentLock() {
        when(mapper.lockDocument(7)).thenAnswer(call -> {
            when(identities.resolvePrincipal(1)).thenReturn(principal(1, 0));
            return state.get();
        });
        rejects(() -> change(2, 0, "one"), 403);
        var order = inOrder(mapper, sessions, permissions, identities);
        order.verify(mapper).lockRequest("DOCUMENT_SECURITY_LEVEL_CHANGE:one");
        order.verify(mapper).lockDocument(7);
        order.verify(sessions).clearCache();
        order.verify(permissions).findUserPermissions(1);
        order.verify(identities).resolvePrincipal(1);
        noWrites();
    }

    @Test
    void revocationWhileWaitingCannotUseCachedPermission() {
        when(mapper.lockDocument(7)).thenAnswer(call -> {
            when(permissions.findUserPermissions(1)).thenReturn(List.of());
            return state.get();
        });
        rejects(() -> change(2, 0, "one"), 403);
        noWrites();
    }

    @Test
    void zeroChunksIsLegalAndSynchronizationFailureDoesNotReturnSuccess() {
        when(mapper.syncChunks(7, (short) 2)).thenReturn(0);
        assertThat(change(2, 0, "empty").securityLevel()).isEqualTo((short) 2);
        doThrow(new DataIntegrityViolationException("sync failed")).when(mapper).syncChunks(7, (short) 3);
        assertThatThrownBy(() -> change(3, 1, "broken")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(history).containsOnlyKeys("empty");
    }

    @Test
    void auditInsertFailurePropagatesWithoutRetryingAbortedTransaction() {
        doThrow(new DataIntegrityViolationException("audit failed")).when(events).insert(any(), anyString(),
                anyString(),
                anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any());
        assertThatThrownBy(() -> change(2, 0, "fail")).isInstanceOf(DataIntegrityViolationException.class);
        verify(events, times(1)).findByRequest("DOCUMENT_SECURITY_LEVEL_CHANGE", "fail");
        assertThat(history).isEmpty();
    }

    @Test
    void unsuccessfulDocumentUpdateCannotProceedToChunksOrAudit() {
        when(mapper.updateLevel(7, (short) 2, 0)).thenReturn(0);
        rejects(() -> change(2, 0, "conflict"), 409);
        verify(mapper, never()).syncChunks(anyLong(), anyShort());
        assertThat(history).isEmpty();
    }

    @Test
    void serviceRejectsInvalidInputWithoutPersistence() {
        rejects(() -> service.change(7, null, null), 400);
        rejects(() -> service.change(7, new DocumentSecurityLevelChange(null, 0L, "原因", "r"), null), 400);
        rejects(() -> service.change(7, new DocumentSecurityLevelChange((short) 2, -1L, "原因", "r"), null), 400);
        rejects(() -> service.change(7, new DocumentSecurityLevelChange((short) 2, 0L, " ", "r"), null), 400);
        rejects(() -> service.change(7, command(2, 0, "a"), "b"), 400);
        verifyNoInteractions(mapper, events);
    }

    private DocumentSecurityLevelResult change(int level, long version, String request) {
        return service.change(7, command(level, version, request), null);
    }

    private static DocumentSecurityLevelChange command(int level, long version, String request) {
        return new DocumentSecurityLevelChange((short) level, version, "定级复核", request);
    }

    private static ResolvedPrincipal principal(long userId, int clearance) {
        return new ResolvedPrincipal(userId, 10, (short) clearance, List.of(), List.of(10L));
    }

    private static void rejects(ThrowingRunnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(IdentityException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(status));
    }

    private void noWrites() {
        verify(mapper, never()).updateLevel(anyLong(), anyShort(), anyLong());
        verify(mapper, never()).syncChunks(anyLong(), anyShort());
        assertThat(history).isEmpty();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
