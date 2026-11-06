package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServicePrincipalContractTest {
    @Test
    void developerLoginIsNeverRecordedAsLocalPasswordAuthentication() {
        EpochSessionService sessions = mock(EpochSessionService.class);
        IdentityService identities = mock(IdentityService.class);
        when(sessions.issue(7L, EpochSessionService.Method.DEV)).thenReturn("fresh-dev-token");
        AuthService auth = new AuthService(mock(PermissionMapper.class), identities, sessions);
        assertThat(auth.devLogin(7).getTokenValue()).isEqualTo("fresh-dev-token");
        verify(identities).resolvePrincipal(7);
        verify(sessions).issue(7L, EpochSessionService.Method.DEV);
    }

    @Test
    void quotaContextUsesResolvedDepartmentInsteadOfPermissionHeadersOrParameters() {
        PermissionMapper permissions = mock(PermissionMapper.class);
        IdentityService identities = mock(IdentityService.class);
        when(identities.quotaPrincipal(7L)).thenReturn(new QuotaPrincipal(7L, 42L));
        AuthService auth = new AuthService(permissions, identities, mock(EpochSessionService.class));

        assertThat(auth.quotaPrincipal(7L)).isEqualTo(new QuotaPrincipal(7L, 42L));
        verify(identities).quotaPrincipal(7L);
        verifyNoInteractions(permissions);
    }
}
