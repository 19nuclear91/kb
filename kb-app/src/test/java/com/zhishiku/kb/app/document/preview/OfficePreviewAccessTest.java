package com.zhishiku.kb.app.document.preview;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.AuthenticationIdentity;
import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.auth.service.OfficeLoginAccess;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import com.zhishiku.kb.app.auth.service.SessionTokenIndex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OfficePreviewAccessTest {
    @Test
    void originalPreviewTokenStaysRevokedAfterEmployeeRestoration() {
        var logic = mock(StpLogic.class);
        var users = mock(IdentityDepartmentMapper.class);
        var tokenSession = mock(SaSession.class);
        when(logic.getLoginIdByToken("original")).thenReturn(7L);
        when(logic.getTokenSessionByToken("original", false)).thenReturn(tokenSession);
        when(tokenSession.get(EpochSessionService.METADATA)).thenReturn("7:1:LOCAL");
        when(users.findAuthenticationIdentity(7)).thenReturn(new AuthenticationIdentity(7, 1, true, true, false));
        var previews = mock(PreviewInfoService.class);
        var access = new OfficePreviewAccess(new OfficeLoginAccess(new EpochSessionService(users, logic, mock(SessionTokenIndex.class)),
                mock(ConversationIdentityGate.class)), previews);
        access.require("original", 7L, 8, 9);
        when(users.findAuthenticationIdentity(7)).thenReturn(new AuthenticationIdentity(7, 2, false, true, false));
        expectStatus(() -> access.require("original", 7L, 8, 9), 401);
        when(users.findAuthenticationIdentity(7)).thenReturn(new AuthenticationIdentity(7, 3, true, true, false));
        expectStatus(() -> access.require("original", 7L, 8, 9), 401);
        verify(previews, times(1)).file(7, 8, 9);
    }
    @Test
    void everyReadUsesOriginalSessionCurrentIdentityAndExactVersionView() {
        var sessions = mock(EpochSessionService.class);
        var identity = mock(ConversationIdentityGate.class);
        var documents = mock(DocumentManagementService.class);
        var access = new OfficePreviewAccess(new OfficeLoginAccess(sessions, identity),
                new PreviewInfoService(documents, new OfficePreviewSettings()));
        when(sessions.check("original")).thenReturn(new EpochSessionService.Session(7, 1, EpochSessionService.Method.LOCAL, false));
        var file = new DocumentFile(8L, 9L, 1, "key", "file.docx", 10L, "application/octet-stream");
        when(documents.file(7, 8, 9L, "view")).thenReturn(file);
        assertThat(access.require("original", 7L, 8, 9).file()).isSameAs(file);
        when(documents.file(7, 8, 9L, "view")).thenThrow(ApiException.notFound("已撤销或候选不可见"));
        expectStatus(() -> access.require("original", 7L, 8, 9), 404);
        verify(sessions, times(2)).check("original");
        verify(identity, times(2)).require(7);
        verify(documents, times(2)).file(7, 8, 9L, "view");
        doThrow(IdentityException.unauthorized("已撤销")).when(sessions).check("original");
        when(sessions.check("new-login")).thenReturn(new EpochSessionService.Session(7, 3, EpochSessionService.Method.LOCAL, false));
        expectStatus(() -> access.require("original", 7L, 8, 9), 401);
    }

    @Test
    void passwordGateDisabledIdentityAndDependencyFailureNeverUseOldPermission() {
        var sessions = mock(EpochSessionService.class);
        var identity = mock(ConversationIdentityGate.class);
        var previews = mock(PreviewInfoService.class);
        var access = new OfficePreviewAccess(new OfficeLoginAccess(sessions, identity), previews);
        when(sessions.check("original")).thenReturn(new EpochSessionService.Session(7, 1, EpochSessionService.Method.LOCAL, false));
        doThrow(IdentityException.passwordChangeRequired("must change")).when(identity).require(7);
        expectStatus(() -> access.require("original", 7L, 8, 9), 403);
        doThrow(IdentityException.disabled("disabled")).when(identity).require(7);
        expectStatus(() -> access.require("original", 7L, 8, 9), 404);
        doThrow(new IllegalStateException("database secret")).when(identity).require(7);
        expectStatus(() -> access.require("original", 7L, 8, 9), 503);
        verifyNoInteractions(previews);
    }

    private static void expectStatus(Runnable request, int expected) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(OfficePreviewFailure.class,
                failure -> assertThat(failure.status()).isEqualTo(expected));
    }
}
