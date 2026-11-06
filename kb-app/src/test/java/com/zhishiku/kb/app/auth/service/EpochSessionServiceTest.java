package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.AuthenticationIdentity;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.zhishiku.kb.app.auth.service.SessionTokenIndex;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpochSessionServiceTest {
    private final IdentityDepartmentMapper users = mock(IdentityDepartmentMapper.class);
    private final StpLogic logic = mock(StpLogic.class);
    private final SaSession tokenSession = mock(SaSession.class);
    private final EpochSessionService service = new EpochSessionService(users, logic, mock(SessionTokenIndex.class));

    @BeforeEach
    void setup() {
        when(users.findAuthenticationIdentity(7)).thenReturn(identity(3, true));
        when(logic.getConfigOrGlobal()).thenReturn(new SaTokenConfig().setTimeout(-1));
        when(logic.createLoginSession(eq(7L), any(SaLoginParameter.class)))
                .thenAnswer(call -> call.getArgument(1, SaLoginParameter.class).getToken());
        when(logic.getTokenSessionByToken(anyString(), eq(true))).thenReturn(tokenSession);
        when(logic.getTokenSessionByToken("existing", false)).thenReturn(tokenSession);
        when(logic.getLoginIdByToken("existing")).thenReturn(7L);
    }

    @Test
    void eachMethodGetsIndependentRandomTokenWithTrustedEpochAndMethod() {
        var tokens = new java.util.HashSet<String>();
        for (var method : EpochSessionService.Method.values()) {
            String token = service.issue(7, method);
            assertThat(tokens.add(token)).isTrue();
            assertThat(token).hasSize(43);
            verify(tokenSession).set(EpochSessionService.METADATA, "7:3:" + method.name());
        }
        var parameters = ArgumentCaptor.forClass(SaLoginParameter.class);
        verify(logic, org.mockito.Mockito.times(3)).createLoginSession(eq(7L), parameters.capture());
        assertThat(parameters.getAllValues()).allSatisfy(parameter -> {
            assertThat(parameter.getIsShare()).isFalse();
            assertThat(parameter.getIsWriteHeader()).isFalse();
            assertThat(parameter.getTimeout()).isEqualTo(2592000);
        });
    }

    @Test
    void revocationDuringIssuanceDiscardsOnlyCandidateEvenIfAccountWasRestored() {
        when(users.findAuthenticationIdentity(7)).thenReturn(identity(3, true), identity(5, true));
        assertStatus(() -> service.issue(7, EpochSessionService.Method.LOCAL), 401);
        var parameters = ArgumentCaptor.forClass(SaLoginParameter.class);
        verify(logic).createLoginSession(eq(7L), parameters.capture());
        verify(logic).logoutByTokenValue(parameters.getValue().getToken());
        verify(logic, never()).logout(any(Object.class));
    }

    @Test
    void missingAndOldEpochAreRejectedWithoutRepairingTokenMetadata() {
        assertStatus(() -> service.check("existing"), 401);
        when(tokenSession.get(EpochSessionService.METADATA)).thenReturn("7:2:LOCAL");
        assertStatus(() -> service.check("existing"), 401);
        verify(tokenSession, never()).set(anyString(), any());
    }

    @Test
    void currentDisabledIdentityIs403AndUnknownDependencyIs503() {
        when(tokenSession.get(EpochSessionService.METADATA)).thenReturn("7:3:DEV");
        when(users.findAuthenticationIdentity(7)).thenReturn(identity(3, false));
        assertStatus(() -> service.check("existing"), 403);
        when(users.findAuthenticationIdentity(7)).thenThrow(new IllegalStateException("private database details"));
        assertStatus(() -> service.check("existing"), 503);
    }

    @Test
    void successfulReadCarriesMethodAndFreshPasswordFlagWithoutPositiveCache() {
        when(tokenSession.get(EpochSessionService.METADATA)).thenReturn("7:3:DEV");
        assertThat(service.check("existing").method()).isEqualTo(EpochSessionService.Method.DEV);
        var databaseTime = java.time.Instant.parse("2026-01-01T00:00:00Z");
        when(users.findAuthenticationIdentity(7)).thenReturn(new AuthenticationIdentity(7, 3, true, true, true, databaseTime));
        assertThat(service.check("existing").mustChangePassword()).isTrue();
        assertThat(service.check("existing").checkedAt()).isEqualTo(databaseTime);
        when(users.findAuthenticationIdentity(7)).thenReturn(identity(4, false));
        assertStatus(() -> service.check("existing"), 401);
    }

    private static AuthenticationIdentity identity(long epoch, boolean enabled) {
        return new AuthenticationIdentity(7, epoch, enabled, true, false);
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(status));
    }
}
