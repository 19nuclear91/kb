package com.zhishiku.kb.app.auth.service.diagnostics;

import com.zhishiku.kb.app.auth.mapper.diagnostics.DiagnosticMapper;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticFacts;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticEntry;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticViews;
import com.zhishiku.kb.app.config.DiagnosticLimits;
import com.zhishiku.kb.common.security.PrincipalType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class DiagnosticEvaluationTest {
    private final DiagnosticMapper mapper = mock(DiagnosticMapper.class);
    private final DiagnosticEvaluator evaluator = new DiagnosticEvaluator(mapper);
    private final DiagnosticTargetLoader.Target target = new DiagnosticTargetLoader.Target(8, "目标", true, (short) 1, Set.of("1:8", "3:5"), false);
    private final DiagnosticAccess.Resource resource = new DiagnosticAccess.Resource(7, "DOCUMENT", 10, 20L, false);

    @Test
    void identityBlockDoesNotReadAclOrVersionsAndNoDocumentHasPriority() {
        var blocked = new DiagnosticTargetLoader.Target(8, "目标", false, (short) 0, Set.of(), false);
        var result = evaluator.evaluate(resource, blocked, List.of());
        assertThat(result.currentVersionAccess().status()).isEqualTo("NOT_EVALUATED");
        assertThat(result.documentPermissions()).allMatch(permission -> permission.grantMatch().equals("NOT_EVALUATED"));
        assertThat(evaluator.evaluate(new DiagnosticAccess.Resource(7, "SPACE", 10, null, false), blocked, List.of())
                .currentVersionAccess().status()).isEqualTo("NOT_APPLICABLE");
        verifyNoInteractions(mapper);
    }

    @Test
    void preservesAllSourcesWhileClearanceBlocksAndMissingPointerIsNotDenied() {
        var rows = List.of(row("SPACE", 1, 8, "view"), row("DOCUMENT", 3, 5, "view"));
        fixedGrants(true, true, false);
        when(mapper.current(anyLong(), anyLong(), anyShort(), any(), any()))
                .thenReturn(new DiagnosticFacts.Current(true, true, false, false, false),
                        new DiagnosticFacts.Current(false, false, false, false, false),
                        new DiagnosticFacts.Current(true, false, true, false, false));
        var assessed = evaluator.evaluate(resource, target, rows);
        assertThat(assessed.documentPermissions().getFirst().sourceKeys()).hasSize(2);
        assertThat(assessed.currentVersionAccess().viewReasonCodes()).containsExactly("CLEARANCE_BLOCKED");
        var missing = evaluator.evaluate(resource, target, rows).currentVersionAccess();
        assertThat(missing.status()).isEqualTo("NO_CURRENT_PUBLISHED_VERSION");
        assertThat(missing.view()).isNull();
        assertThatThrownBy(() -> evaluator.evaluate(resource, target, rows)).isInstanceOf(DiagnosticException.class);
    }

    @Test
    void downloadWithoutViewIsDenied() {
        fixedGrants(false, false, true);
        when(mapper.current(anyLong(), anyLong(), anyShort(), any(), any()))
                .thenReturn(new DiagnosticFacts.Current(true, true, true, false, false));
        var result = evaluator.evaluate(resource, target, List.of(row("DOCUMENT", 1, 8, "download")));
        assertThat(result.currentVersionAccess().downloadReasonCodes()).containsExactly("VIEW_NOT_GRANTED");
        assertThat(result.documentPermissions().get(1).grantMatch()).isEqualTo("MATCHED");
    }

    @Test
    void globalManageHasOnlyFixedManagementSourceAndNeverGrantsReading() {
        var global = new DiagnosticTargetLoader.Target(8, "目标", true, (short) 1, Set.of("1:8"), true);
        when(mapper.grants(10, 20L, global.keys(), List.of(), true)).thenReturn(DiagnosticEvaluator.SPACE_PERMISSIONS.stream()
                .map(permission -> new DiagnosticFacts.Grant(permission, permission.equals("manage"), false)).toList());
        when(mapper.current(10, 20, (short) 1, global.keys(), List.of()))
                .thenReturn(new DiagnosticFacts.Current(true, true, true, false, false));
        var result = evaluator.evaluate(resource, global, List.of());
        assertThat(result.sources()).containsExactly(new DiagnosticViews.Source("GLOBAL_MANAGEMENT", "GLOBAL_MANAGEMENT",
                null, null, "全局空间管理资格", "manage", "EXISTING"));
        assertThat(result.spacePermissions().getLast().sourceKeys()).containsExactly("GLOBAL_MANAGEMENT");
        assertThat(result.spacePermissions().subList(0, 5)).allMatch(permission -> permission.grantMatch().equals("NOT_MATCHED"));
        assertThat(result.documentPermissions()).allMatch(permission -> permission.sourceKeys().isEmpty());
        assertThat(result.currentVersionAccess().view()).isEqualTo("DENIED");
        assertThat(result.currentVersionAccess().downloadReasonCodes()).containsExactly("VIEW_NOT_GRANTED", "DOWNLOAD_NOT_GRANTED");
    }

    @Test
    void archivedResourcePreservesManageAndBlocksAllOtherPermissions() {
        var archived = new DiagnosticAccess.Resource(7, "DOCUMENT", 10, 20L, true);
        var rows = List.of(row("SPACE", 1, 8, "view"), row("SPACE", 1, 8, "manage"));
        when(mapper.grants(10, 20L, target.keys(), rows, false)).thenReturn(DiagnosticEvaluator.SPACE_PERMISSIONS.stream()
                .map(permission -> new DiagnosticFacts.Grant(permission, Set.of("view", "manage").contains(permission), permission.equals("view"))).toList());
        when(mapper.current(10, 20, (short) 1, target.keys(), rows))
                .thenReturn(new DiagnosticFacts.Current(true, true, true, false, false));
        var result = evaluator.evaluate(archived, target, rows);
        assertThat(result.spacePermissions().getLast().scopeCondition()).isEqualTo("PASSED");
        assertThat(result.spacePermissions().getLast().grantMatch()).isEqualTo("MATCHED");
        assertThat(result.spacePermissions().subList(0, 5)).allSatisfy(permission -> {
            assertThat(permission.scopeCondition()).isEqualTo("BLOCKED");
            assertThat(permission.reasonCodes()).contains("SPACE_ARCHIVED");
        });
        assertThat(result.documentPermissions()).allSatisfy(permission -> assertThat(permission.reasonCodes()).contains("SPACE_ARCHIVED"));
        assertThat(result.currentVersionAccess().viewReasonCodes()).containsExactly("SPACE_ARCHIVED");
        assertThat(result.currentVersionAccess().download()).isEqualTo("DENIED");
        assertThat(result.sources()).hasSize(2);
    }

    @Test
    void twoRoleSourcesRemainDistinctAndRevokingOneKeepsTheOtherEffective() {
        var member = new DiagnosticTargetLoader.Target(8, "目标", true, (short) 1, Set.of("1:8", "2:2", "2:3"), false);
        var rows = List.of(row("SPACE", 2, 2, "view"), row("SPACE", 2, 3, "view"));
        fixedGrants(true, true, false);
        when(mapper.current(anyLong(), anyLong(), anyShort(), any(), any()))
                .thenReturn(new DiagnosticFacts.Current(true, true, true, true, false));
        var before = evaluator.evaluate(resource, member, rows);
        assertThat(before.documentPermissions().getFirst().sourceKeys()).containsExactly("SPACE:2:2:view", "SPACE:2:3:view");
        var removed = new DiagnosticEntry("SPACE", PrincipalType.ROLE, 2, "view");
        var intent = new DiagnosticInput.Intent("SPACE_REVOKE", "REVOKE", List.of(removed));
        when(mapper.existingEntries(10, 20L, intent.entries())).thenReturn(List.of(removed.key()));
        var overlay = new DiagnosticOverlay(mapper, DiagnosticLimits.defaults()).apply(resource, member, rows, intent);
        var after = evaluator.evaluate(resource, member, overlay.after());
        assertThat(after.sources()).extracting(DiagnosticViews.Source::key).containsExactly("SPACE:2:3:view");
        assertThat(after.documentPermissions().getFirst().sourceKeys()).containsExactly("SPACE:2:3:view");
        assertThat(after.currentVersionAccess().view()).isEqualTo(before.currentVersionAccess().view()).isEqualTo("ALLOWED");
        assertThat(overlay.changes().getFirst().outcome()).isEqualTo("REMOVED");
    }

    @Test
    void overlayPreservesOtherSourcesAndNeverExpandsUnrelatedSubjects() {
        var before = List.of(row("SPACE", 1, 8, "view"), row("SPACE", 3, 5, "view"));
        var remove = new DiagnosticEntry("SPACE", PrincipalType.USER, 8, "view");
        var absentRole = new DiagnosticEntry("SPACE", PrincipalType.ROLE, 999, "download");
        var intent = new DiagnosticInput.Intent("SPACE_REVOKE", "REVOKE", List.of(remove, absentRole));
        when(mapper.existingEntries(10, 20L, intent.entries())).thenReturn(List.of(remove.key()));
        var result = new DiagnosticOverlay(mapper, DiagnosticLimits.defaults()).apply(resource, target, before, intent);
        assertThat(result.after()).containsExactly(before.get(1));
        assertThat(result.changes()).extracting(DiagnosticViews.Change::outcome).containsExactly("REMOVED", "UNCHANGED");
        var grant = new DiagnosticInput.Intent("SPACE_GRANT", "GRANT", List.of(remove));
        when(mapper.existingEntries(10, 20L, grant.entries())).thenReturn(List.of(remove.key()));
        assertThat(new DiagnosticOverlay(mapper, DiagnosticLimits.defaults()).apply(resource, target, before, grant).after()).isEqualTo(before);
    }

    private void fixedGrants(boolean spaceView, boolean documentView, boolean download) {
        when(mapper.grants(anyLong(), any(), any(), any(), anyBoolean())).thenReturn(DiagnosticEvaluator.SPACE_PERMISSIONS.stream()
                .map(permission -> new DiagnosticFacts.Grant(permission, permission.equals("view") && spaceView,
                        permission.equals("view") && documentView || permission.equals("download") && download)).toList());
    }

    @Test
    void hypotheticalViewCanChangeSourcesWhileClearanceStillBlocks() {
        var entry = new DiagnosticEntry("DOCUMENT", PrincipalType.USER, 8, "view");
        var intent = new DiagnosticInput.Intent("DOCUMENT_GRANT", "GRANT", List.of(entry));
        when(mapper.existingEntries(10, 20L, intent.entries())).thenReturn(List.of());
        when(mapper.grants(anyLong(), any(), any(), any(), anyBoolean())).thenAnswer(call -> {
            List<DiagnosticFacts.Acl> rows = call.getArgument(3);
            return DiagnosticEvaluator.SPACE_PERMISSIONS.stream().map(permission -> new DiagnosticFacts.Grant(permission,
                    false, permission.equals("view") && !rows.isEmpty())).toList();
        });
        when(mapper.current(anyLong(), anyLong(), anyShort(), any(), any()))
                .thenReturn(new DiagnosticFacts.Current(true, true, false, false, false));
        var before = evaluator.evaluate(resource, target, List.of());
        var hypothesis = new DiagnosticOverlay(mapper, DiagnosticLimits.defaults()).apply(resource, target, List.of(), intent);
        var after = evaluator.evaluate(resource, target, hypothesis.after());
        assertThat(before.documentPermissions().getFirst().grantMatch()).isEqualTo("NOT_MATCHED");
        assertThat(after.documentPermissions().getFirst().grantMatch()).isEqualTo("MATCHED");
        assertThat(after.currentVersionAccess().view()).isEqualTo("DENIED");
        assertThat(after.currentVersionAccess().viewReasonCodes()).containsExactly("CLEARANCE_BLOCKED");
        assertThat(hypothesis.changes()).extracting(DiagnosticViews.Change::outcome).containsExactly("ADDED");
        assertThat(after.sources()).extracting(DiagnosticViews.Source::origin).containsExactly("PROPOSED");
    }

    @Test
    void documentRevokeKeepsSpaceGrantAndSelfManageRemovalDoesNotChangeActorContext() {
        var space = row("SPACE", 1, 8, "download");
        var exception = row("DOCUMENT", 1, 8, "download");
        var remove = new DiagnosticEntry("DOCUMENT", PrincipalType.USER, 8, "download");
        var intent = new DiagnosticInput.Intent("DOCUMENT_REVOKE", "REVOKE", List.of(remove));
        when(mapper.existingEntries(10, 20L, intent.entries())).thenReturn(List.of(remove.key()));
        var result = new DiagnosticOverlay(mapper, DiagnosticLimits.defaults()).apply(resource, target, List.of(space, exception), intent);
        assertThat(result.after()).containsExactly(space);
        var manage = new DiagnosticEntry("SPACE", PrincipalType.USER, 8, "manage");
        var revokeManage = new DiagnosticInput.Intent("SPACE_REVOKE", "REVOKE", List.of(manage));
        when(mapper.existingEntries(10, 20L, revokeManage.entries())).thenReturn(List.of(manage.key()));
        var selfResource = new DiagnosticAccess.Resource(8, "SPACE", 10, 20L, false);
        var before = List.of(row("SPACE", 1, 8, "manage"));
        assertThat(new DiagnosticOverlay(mapper, DiagnosticLimits.defaults()).apply(selfResource, target, before, revokeManage).after()).isEmpty();
        assertThat(before).hasSize(1);
        assertThat(selfResource.actorId()).isEqualTo(8);
        verify(mapper, never()).existingRoles(any());
    }

    @Test
    void missingRoleGrantAndUnionLimitFailAtomically() {
        var role = new DiagnosticEntry("SPACE", PrincipalType.ROLE, 3, "view");
        var roleIntent = new DiagnosticInput.Intent("SPACE_GRANT", "GRANT", List.of(role));
        when(mapper.existingRoles(Set.of(3L))).thenReturn(List.of());
        assertThatThrownBy(() -> new DiagnosticOverlay(mapper, DiagnosticLimits.defaults()).apply(resource, target, List.of(), roleIntent))
                .isInstanceOfSatisfying(DiagnosticException.class, error -> assertThat(error.reason()).isEqualTo(DiagnosticException.Reason.INVALID_INPUT));
        var personal = new DiagnosticEntry("SPACE", PrincipalType.USER, 8, "download");
        var intent = new DiagnosticInput.Intent("SPACE_GRANT", "GRANT", List.of(personal));
        when(mapper.existingEntries(10, 20L, intent.entries())).thenReturn(List.of());
        var limits = new DiagnosticLimits(65536, 4096, 1, 524288, 5000);
        assertThatThrownBy(() -> new DiagnosticOverlay(mapper, limits).apply(resource, target, List.of(row("SPACE", 3, 5, "view")), intent))
                .isInstanceOfSatisfying(DiagnosticException.class, error -> assertThat(error.reason()).isEqualTo(DiagnosticException.Reason.DIAGNOSTIC_LIMIT_EXCEEDED));
    }

    private static DiagnosticFacts.Acl row(String kind, int type, long id, String permission) {
        return new DiagnosticFacts.Acl(kind, type, id, type + ":" + id, "显示名", permission, false);
    }
}
