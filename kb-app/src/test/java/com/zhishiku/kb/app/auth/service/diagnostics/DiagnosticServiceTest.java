package com.zhishiku.kb.app.auth.service.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityQueryMapper;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.mapper.diagnostics.DiagnosticMapper;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticFacts;
import com.zhishiku.kb.app.config.DiagnosticLimits;
import com.zhishiku.kb.common.security.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;

class DiagnosticServiceTest {
    private final DiagnosticAccess access = mock(DiagnosticAccess.class);
    private final DiagnosticTargetLoader targets = mock(DiagnosticTargetLoader.class);
    private final DiagnosticMapper mapper = mock(DiagnosticMapper.class);
    private final IdentityQueryMapper users = mock(IdentityQueryMapper.class);
    private final IdentityDepartmentMapper identities = mock(IdentityDepartmentMapper.class);
    private final DiagnosticLimits limits = DiagnosticLimits.defaults();
    private final DiagnosticService service;
    private final DiagnosticAccess.Resource resource = new DiagnosticAccess.Resource(7, "SPACE", 10, null, false);

    DiagnosticServiceTest() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DiagnosticService(access, new DiagnosticDatabase(manager, limits, new ObjectMapper()), targets,
                mapper, new DiagnosticEvaluator(mapper), new DiagnosticOverlay(mapper, limits), limits, users, identities);
        when(access.require("SPACE", 10, null, false)).thenReturn(resource);
        when(mapper.evaluatedAt()).thenReturn(OffsetDateTime.parse("2026-11-25T08:00:00Z"));
    }

    @Test
    void denialDoesNotParseTargetOrReadFacts() {
        when(access.require("SPACE", 10, null, false)).thenThrow(new DiagnosticException(DiagnosticException.Reason.FORBIDDEN));
        @SuppressWarnings("unchecked")
        Supplier<com.fasterxml.jackson.databind.JsonNode> body = mock(Supplier.class);
        assertThatThrownBy(() -> service.diagnose("SPACE", 10, false, body)).isInstanceOf(DiagnosticException.class);
        verifyNoInteractions(body, targets, mapper, users, identities);
    }

    @Test
    void previewReadsTargetAndSourcesOnceAndReturnsEveryChangeWithoutWrites() throws Exception {
        var target = new DiagnosticTargetLoader.Target(8, "目标", true, (short) 1, Set.of("1:8", "3:5"), false);
        var row = new DiagnosticFacts.Acl("SPACE", 3, 5, "3:5", "部门", "view", false);
        when(targets.load(8)).thenReturn(target);
        var concurrentRows = new java.util.concurrent.atomic.AtomicReference<>(List.of(row));
        when(mapper.sources(10, null, target.keys(), 1001)).thenAnswer(call -> concurrentRows.get());
        when(mapper.grants(eq(10L), isNull(), any(), any(), eq(false))).thenAnswer(call -> {
            // 在第一次评估后改变下一次请求的事实，当前 before／after 仍使用已捕获的行集。
            List<DiagnosticFacts.Acl> captured = call.getArgument(3);
            concurrentRows.set(List.of());
            return DiagnosticEvaluator.SPACE_PERMISSIONS.stream()
                    .map(permission -> new DiagnosticFacts.Grant(permission, permission.equals("view") && !captured.isEmpty(),
                            permission.equals("view") && !captured.isEmpty())).toList();
        });
        when(mapper.existingEntries(eq(10L), isNull(), any())).thenReturn(List.of());
        var response = service.diagnose("SPACE", 10, true, () -> DiagnosticInputTest.read(
                "{\"userId\":8,\"intent\":{\"type\":\"SPACE_REVOKE\",\"items\":[{\"principalType\":\"ROLE\",\"principalId\":999,\"permission\":\"view\"}]}}"));
        var data = new ObjectMapper().valueToTree(response).path("data");
        assertThat(data.path("before")).isEqualTo(data.path("after"));
        assertThat(data.path("changes").get(0).path("outcome").textValue()).isEqualTo("UNCHANGED");
        assertThat(data.path("resource").path("documentId").isNull()).isTrue();
        verify(targets).load(8);
        verify(mapper).sources(10, null, target.keys(), 1001);
        verify(mapper, never()).existingRoles(any());
        verifyNoInteractions(users, identities);
        var next = service.diagnose("SPACE", 10, false, () -> DiagnosticInputTest.read("{\"userId\":8}"));
        assertThat(next.data().before().spacePermissions().getFirst().grantMatch()).isEqualTo("NOT_MATCHED");
    }

    @Test
    void sourceLimitFailsBeforePartialEvaluation() {
        var target = new DiagnosticTargetLoader.Target(8, "目标", true, (short) 1, Set.of("1:8"), false);
        when(targets.load(8)).thenReturn(target);
        var rows = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(id -> new DiagnosticFacts.Acl("SPACE", 1, id + 1, "1:8", "显示名", "view", false)).toList();
        when(mapper.sources(10, null, target.keys(), 1001)).thenReturn(rows);
        assertThatThrownBy(() -> service.diagnose("SPACE", 10, false, () -> DiagnosticInputTest.read("{\"userId\":8}")))
                .isInstanceOfSatisfying(DiagnosticException.class,
                        error -> assertThat(error.reason()).isEqualTo(DiagnosticException.Reason.DIAGNOSTIC_LIMIT_EXCEEDED));
        verify(mapper, never()).grants(anyLong(), any(), any(), any(), anyBoolean());
    }

    @Test
    void minimalSearchEscapesLiteralQueryAndCursorBindsIdentityEpochAndResource() throws Exception {
        var actor = new IdentityRecord(7L, "private", "操作者", 5L, (short) 1, true, 0L, null, null);
        when(identities.findIdentity(7)).thenReturn(actor);
        when(users.principals(PrincipalType.USER, "%\\%\\_名字%", null, 2)).thenReturn(List.of(
                new IdentityQueryViews.PrincipalOption(PrincipalType.USER, 8, "目标"),
                new IdentityQueryViews.PrincipalOption(PrincipalType.USER, 9, "下一位")));
        var response = service.search("SPACE", 10, () -> DiagnosticInputTest.read("{\"q\":\"%_名字\",\"limit\":1}"));
        var data = new ObjectMapper().valueToTree(response).path("data");
        assertThat(data.path("items").get(0).size()).isEqualTo(2);
        assertThat(data.path("hasMore").booleanValue()).isTrue();
        String cursor = data.path("nextCursor").textValue();
        String continued = "{\"q\":\"%_名字\",\"limit\":1,\"cursor\":\"" + cursor + "\"}";
        when(users.principals(PrincipalType.USER, "%\\%\\_名字%", 8L, 2)).thenReturn(List.of(
                new IdentityQueryViews.PrincipalOption(PrincipalType.USER, 9, "下一位")));
        assertThat(service.search("SPACE", 10, () -> DiagnosticInputTest.read(continued)).data().items())
                .extracting(com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticViews.User::userId).containsExactly("9");
        clearInvocations(users);
        when(access.require("SPACE", 11, null, false)).thenReturn(new DiagnosticAccess.Resource(7, "SPACE", 11, null, false));
        assertInvalidCursor(() -> service.search("SPACE", 11, () -> DiagnosticInputTest.read(continued)));
        assertInvalidCursor(() -> service.search("SPACE", 10, () -> DiagnosticInputTest.read(continued.replace("%_名字", "其他"))));
        when(access.require("SPACE", 10, null, false)).thenReturn(new DiagnosticAccess.Resource(9, "SPACE", 10, null, false));
        when(identities.findIdentity(9)).thenReturn(new IdentityRecord(9L, "other", "另一操作者", 5L, (short) 1, true, 0L, null, null));
        assertInvalidCursor(() -> service.search("SPACE", 10, () -> DiagnosticInputTest.read(continued)));
        when(access.require("SPACE", 10, null, false)).thenReturn(resource);
        actor.setAuthEpoch(2L);
        assertThatThrownBy(() -> service.search("SPACE", 10, () -> DiagnosticInputTest.read(
                "{\"q\":\"%_名字\",\"limit\":1,\"cursor\":\"" + cursor + "\"}")))
                .isInstanceOfSatisfying(DiagnosticException.class,
                        error -> assertThat(error.reason()).isEqualTo(DiagnosticException.Reason.INVALID_INPUT));
        verifyNoInteractions(targets, mapper);
        verifyNoInteractions(users);
    }

    private static void assertInvalidCursor(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(DiagnosticException.class,
                error -> assertThat(error.reason()).isEqualTo(DiagnosticException.Reason.INVALID_INPUT));
    }
}
