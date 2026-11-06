package com.zhishiku.kb.app.auth.sso;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.session.SaSession;
import com.zhishiku.kb.app.auth.model.AuthenticationIdentity;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.SsoBindingMapper;
import com.zhishiku.kb.app.auth.model.ExternalIdentityRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.LoginThrottle;
import com.zhishiku.kb.common.security.IdentityException;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.zhishiku.kb.app.auth.service.SessionTokenIndex;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SsoSecurityServiceTest {
    @Test void freshPasswordProofUsesThrottleAndInvalidatesOnAnyHashChange() {
        var mapper = mock(IdentityDepartmentMapper.class); var identities = mock(IdentityService.class);
        var throttle = mock(LoginThrottle.class); var encoder = new BCryptPasswordEncoder(4);
        var service = new SsoLocalProof(mapper, identities, encoder, throttle);
        var user = new IdentityRecord(); user.setId(7L); user.setUsername("alice");
        when(mapper.findIdentity(7)).thenReturn(user);
        when(mapper.findPasswordHashById(7)).thenReturn(encoder.encode("password"));
        when(mapper.findMustChangePasswordById(7)).thenReturn(false);
        for (String password : new String[]{null, "", "wrong"})
            assertThatThrownBy(() -> service.verify(7, password)).isInstanceOf(SsoException.class);
        String proof = service.verify(7, "password");
        service.unchanged(7, proof);
        when(mapper.findPasswordHashById(7)).thenReturn(encoder.encode("password"));
        assertThatThrownBy(() -> service.unchanged(7, proof)).isInstanceOf(SsoException.class);
        when(mapper.findMustChangePasswordById(7)).thenReturn(true);
        assertThatThrownBy(() -> service.verify(7, "password")).isInstanceOfSatisfying(SsoException.class,
                e -> assertThat(e.code()).isEqualTo("PASSWORD_CHANGE_REQUIRED"));
        when(mapper.findMustChangePasswordById(7)).thenReturn(false);
        when(identities.resolvePrincipal(7)).thenThrow(IdentityException.disabled("停用"));
        assertThatThrownBy(() -> service.verify(7, "password")).isInstanceOfSatisfying(SsoException.class,
                e -> assertThat(e.code()).isEqualTo("USER_DISABLED"));
        when(throttle.isThrottled("alice")).thenReturn(true);
        assertThatThrownBy(() -> service.verify(7, "password")).isInstanceOf(SsoException.class);
        verify(throttle).clear("alice");
    }
    @Test void missingHashStillChecksDummyAndCannotIssueProof() {
        var mapper = mock(IdentityDepartmentMapper.class); var identities = mock(IdentityService.class);
        var encoder = mock(BCryptPasswordEncoder.class); var throttle = mock(LoginThrottle.class);
        var service = new SsoLocalProof(mapper, identities, encoder, throttle);
        assertThatThrownBy(() -> service.verify(7, "password")).isInstanceOf(SsoException.class);
        verify(encoder).matches(eq("password"), anyString());
        verifyNoInteractions(identities);
    }
    @Test void bindingLocksUserRechecksProofAndHandlesConflictWithoutLeakingOwner() {
        var mapper = mock(IdentityDepartmentMapper.class); var writes = mock(SsoBindingMapper.class);
        var proof = mock(SsoLocalProof.class); var audit = mock(SsoAudit.class);
        var settings = new SsoSettings(SsoSettingsTest.configured("feishu"), new MockEnvironment());
        var service = new SsoBindings(mapper, writes, proof, settings, audit);
        var flows = new SsoFlows(mock(SsoFlowStore.class), settings, Clock.systemUTC());
        var f = flows.start("feishu", "BIND", null, 7L, "session", "credential").flow(); f.setIdentity(SsoFlowsTest.identity());
        when(mapper.findIdentityForUpdate(7)).thenReturn(new IdentityRecord());
        when(writes.insertIfAbsent(eq(7L), anyString(), anyString())).thenReturn(0);
        when(mapper.findExternalIdentity(anyString(), anyString())).thenReturn(new ExternalIdentityRecord(1L, 7L, "provider", "subject", true));
        service.bind(f);
        var order = inOrder(mapper, proof, writes, audit);
        order.verify(mapper).findIdentityForUpdate(7);
        order.verify(proof).unchanged(7, "credential");
        order.verify(writes).insertIfAbsent(7, f.getIdentity().provider(), "OpenA");
        order.verify(mapper).findExternalIdentity(f.getIdentity().provider(), "OpenA");
        order.verify(audit).terminal(f.getFlowId(), "BIND", "feishu", 7L, true, "ALREADY_BOUND");
        for (var row : List.of(new ExternalIdentityRecord(1L, 8L, "p", "s", true),
                new ExternalIdentityRecord(1L, 7L, "p", "s", false))) {
            when(mapper.findExternalIdentity(anyString(), anyString())).thenReturn(row);
            assertThatThrownBy(() -> service.bind(f)).isInstanceOfSatisfying(SsoException.class,
                    e -> assertThat(e.code()).isEqualTo("BINDING_CONFLICT")).hasMessage("企业认证请求未完成");
        }
        when(mapper.findExternalIdentity(anyString(), anyString())).thenReturn(new ExternalIdentityRecord(1L, 7L, "p", "s", true));
        doThrow(new IllegalStateException("audit failed")).when(audit).terminal(anyString(), anyString(), anyString(), anyLong(), eq(true), anyString());
        assertThatThrownBy(() -> service.bind(f)).isInstanceOf(IllegalStateException.class);
    }
    @Test void statusShowsDisabledConfiguredConnectionsWithoutIdentityData() {
        var mapper = mock(IdentityDepartmentMapper.class); var proof = mock(SsoLocalProof.class);
        var p = SsoSettingsTest.configured("feishu"); p.getConnections().get("feishu").setEnabled(false);
        var service = new SsoBindings(mapper, mock(SsoBindingMapper.class), proof, new SsoSettings(p, new MockEnvironment()), mock(SsoAudit.class));
        when(mapper.listExternalIdentities(eq(7L), anyString())).thenReturn(List.of(new ExternalIdentityRecord(22L, 7L, "private", "private", true)));
        var status = service.status(7);
        assertThat(status).hasSize(3);
        assertThat(status).filteredOn(s -> s.connectionId().equals("feishu")).singleElement().satisfies(s -> {
            assertThat(s.bound()).isTrue(); assertThat(s.connectionEnabled()).isFalse();
        });
        verify(proof).current(7, false);
    }
    @Test void bindingRequiresSameOriginalTokenAndLoginCreatesIndependentUnpublishedSession() {
        var logic = mock(StpLogic.class); var service = new SsoSession(epochs(logic));
        when(logic.getLoginIdByToken("original")).thenReturn("7");
        when(logic.getLoginIdByToken("replacement")).thenReturn("7");
        when(logic.getLoginIdByToken("other")).thenReturn("8");
        var f = new SsoFlow(); f.setOwnerUserId(7L); f.setSessionDigest(SsoCrypto.digest("original"));
        service.original(f, "original");
        for (String token : new String[]{null, "missing", "replacement", "other"})
            assertThatThrownBy(() -> service.original(f, token)).isInstanceOf(SsoException.class);
        when(logic.getConfigOrGlobal()).thenReturn(new SaTokenConfig());
        when(logic.createLoginSession(eq(7L), any(SaLoginParameter.class)))
                .thenAnswer(call -> call.getArgument(1, SaLoginParameter.class).getToken());
        String issued = service.issue(7);
        assertThat(issued).hasSize(43);
        var parameter = ArgumentCaptor.forClass(SaLoginParameter.class);
        verify(logic).createLoginSession(eq(7L), parameter.capture());
        assertThat(parameter.getValue().getIsShare()).isFalse();
        assertThat(parameter.getValue().getIsWriteHeader()).isFalse();
        assertThat(parameter.getValue().getToken()).hasSize(43);
        assertThat(parameter.getValue().getTimeout()).isPositive();
        verify(logic, never()).setTokenValue(anyString());
        service.revoke(issued); verify(logic).logoutByTokenValue(issued);
        verify(logic, never()).logout(any(Object.class));
    }
    @Test void partiallyFailedIssuanceAttemptsToRevokeOnlyItsKnownRandomToken() {
        var logic = mock(StpLogic.class);
        when(logic.getConfigOrGlobal()).thenReturn(new SaTokenConfig().setTimeout(-1));
        when(logic.createLoginSession(eq(7L), any(SaLoginParameter.class))).thenThrow(new IllegalStateException("redis failed"));
        assertThatThrownBy(() -> new SsoSession(epochs(logic)).issue(7)).isInstanceOf(SsoException.class);
        var parameter = ArgumentCaptor.forClass(SaLoginParameter.class);
        verify(logic).createLoginSession(eq(7L), parameter.capture());
        verify(logic).logoutByTokenValue(parameter.getValue().getToken());
        verify(logic, never()).logout(any(Object.class));
        assertThat(parameter.getValue().getTimeout()).isEqualTo(2592000);
    }

    private static EpochSessionService epochs(StpLogic logic) {
        var users = mock(IdentityDepartmentMapper.class);
        when(users.findAuthenticationIdentity(7)).thenReturn(new AuthenticationIdentity(7, 1, true, true, false));
        var tokenSession = mock(SaSession.class);
        when(tokenSession.get(EpochSessionService.METADATA)).thenReturn("7:1:LOCAL");
        when(logic.getTokenSessionByToken(anyString(), anyBoolean())).thenReturn(tokenSession);
        return new EpochSessionService(users, logic, mock(SessionTokenIndex.class));
    }
}
