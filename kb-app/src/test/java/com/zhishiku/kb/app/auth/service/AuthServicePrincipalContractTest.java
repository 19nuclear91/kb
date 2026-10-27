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
    void quotaContextUsesResolvedDepartmentInsteadOfPermissionHeadersOrParameters() {
        PermissionMapper permissions = mock(PermissionMapper.class);
        IdentityService identities = mock(IdentityService.class);
        when(identities.quotaPrincipal(7L)).thenReturn(new QuotaPrincipal(7L, 42L));
        AuthService auth = new AuthService(permissions, identities);

        assertThat(auth.quotaPrincipal(7L)).isEqualTo(new QuotaPrincipal(7L, 42L));
        verify(identities).quotaPrincipal(7L);
        verifyNoInteractions(permissions);
    }
}
