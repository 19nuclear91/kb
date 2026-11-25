package com.zhishiku.kb.app.auth.service.diagnostics;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentAdminQueryMapper;
import com.zhishiku.kb.app.document.model.DocumentAdminView;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DiagnosticAccessTest {
    private final AdminQueryAccess access = mock(AdminQueryAccess.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final SpaceQueryService spaces = mock(SpaceQueryService.class);
    private final DocumentAdminQueryMapper documents = mock(DocumentAdminQueryMapper.class);
    private final SqlSessionTemplate sessions = mock(SqlSessionTemplate.class);
    private final PermissionService.PrincipalHandle actor = mock(PermissionService.PrincipalHandle.class);
    private final DiagnosticAccess subject = new DiagnosticAccess(access, permissions, spaces, documents, sessions);

    @Test
    void functionalDenialPrecedesResourceReads() {
        when(access.require(Permission.AUTHORIZATION_DIAGNOSE)).thenThrow(QueryException.forbidden());
        assertReason(() -> subject.require("SPACE", 10, null, false), DiagnosticException.Reason.FORBIDDEN);
        verifyNoInteractions(spaces, permissions, documents);
        verify(sessions).clearCache();
    }

    @Test
    void diagnosticPermissionOrProposedSelfManageDoesNotCreateResourceScope() {
        when(access.require(Permission.AUTHORIZATION_DIAGNOSE)).thenReturn(actor);
        assertReason(() -> subject.require("SPACE", 10, null, true), DiagnosticException.Reason.NOT_FOUND);
        verify(spaces).findManaged(actor, 10);
    }

    @Test
    void archivedSpaceAndOrdinaryExplanationDoNotRequireReadingContent() {
        when(access.require(Permission.AUTHORIZATION_DIAGNOSE)).thenReturn(actor);
        when(actor.userId()).thenReturn(7L);
        when(actor.functionalPermissions()).thenReturn(Set.of("authorization:diagnose"));
        SpaceRecord space = new SpaceRecord();
        space.setArchived(true);
        when(spaces.findManaged(actor, 10)).thenReturn(space);
        assertThat(subject.require("SPACE", 10, null, false))
                .isEqualTo(new DiagnosticAccess.Resource(7, "SPACE", 10, null, true));
        assertReason(() -> subject.require("SPACE", 10, null, true), DiagnosticException.Reason.FORBIDDEN);
        when(actor.functionalPermissions()).thenReturn(Set.of("authorization:diagnose", "space:grant-manage"));
        assertThat(subject.require("SPACE", 10, null, true).actorId()).isEqualTo(7);
        verifyNoInteractions(permissions, documents);
    }

    @Test
    void documentUsesCurrentOwnershipAndCannotReuseOldSpaceAfterMove() {
        when(access.require(Permission.AUTHORIZATION_DIAGNOSE)).thenReturn(actor);
        when(actor.functionalPermissions()).thenReturn(Set.of("authorization:diagnose"));
        when(permissions.resolve(actor, "manage")).thenReturn(PermissionScope.of(7, (short) 1, List.of(10L), Set.of("1:7")));
        when(documents.list(false, List.of(10L), null, 20L, null, null, 1))
                .thenReturn(List.of(new DocumentAdminView(20, 10, false)), List.of());
        when(spaces.findManaged(actor, 10)).thenReturn(new SpaceRecord());
        assertThat(subject.require("DOCUMENT", 20, null, false).spaceId()).isEqualTo(10);
        assertReason(() -> subject.require("DOCUMENT", 20, null, false), DiagnosticException.Reason.NOT_FOUND);
        assertReason(() -> subject.require("SPACE", 10, 30L, false), DiagnosticException.Reason.NOT_FOUND);
    }

    private static void assertReason(Runnable operation, DiagnosticException.Reason reason) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(DiagnosticException.class,
                error -> assertThat(error.reason()).isEqualTo(reason));
    }

    @Test
    void spaceObservationStaysInPathSpaceEvenWhenActorManagesBothSpaces() {
        when(access.require(Permission.AUTHORIZATION_DIAGNOSE)).thenReturn(actor);
        when(actor.userId()).thenReturn(7L);
        when(actor.functionalPermissions()).thenReturn(Set.of("authorization:diagnose"));
        when(permissions.resolve(actor, "manage")).thenReturn(PermissionScope.of(7, (short) 1, List.of(10L, 11L), Set.of("1:7")));
        when(documents.list(false, List.of(10L, 11L), 10L, 20L, null, null, 1))
                .thenReturn(List.of(new DocumentAdminView(20, 10, false)), List.of());
        when(spaces.findManaged(actor, 10)).thenReturn(new SpaceRecord());
        assertThat(subject.require("SPACE", 10, 20L, false))
                .isEqualTo(new DiagnosticAccess.Resource(7, "SPACE", 10, 20L, false));
        assertReason(() -> subject.require("SPACE", 10, 20L, false), DiagnosticException.Reason.NOT_FOUND);
        when(documents.list(false, List.of(10L, 11L), 11L, 20L, null, null, 1))
                .thenReturn(List.of(new DocumentAdminView(20, 11, false)));
        when(spaces.findManaged(actor, 11)).thenReturn(new SpaceRecord());
        assertThat(subject.require("SPACE", 11, 20L, false).spaceId()).isEqualTo(11);
    }
}
