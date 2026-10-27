package com.zhishiku.kb.app.auth.sso;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.ExternalIdentityRecord;
import com.zhishiku.kb.app.auth.model.LoginResult;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.common.security.IdentitySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SsoFacadeTest {
    private final SsoFlows flows = mock(SsoFlows.class);
    private final SsoSession session = mock(SsoSession.class);
    private final SsoLocalProof proof = mock(SsoLocalProof.class);
    private final SsoBindings bindings = mock(SsoBindings.class);
    private final IdentityDepartmentMapper mapper = mock(IdentityDepartmentMapper.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final SsoAudit audit = mock(SsoAudit.class);
    private final ExternalLoginProvider provider = mock(ExternalLoginProvider.class);
    private SsoFacade facade;
    private SsoFlow f;
    private String browser;

    @BeforeEach
    void setup() {
        when(provider.platform()).thenReturn("feishu");
        facade = new SsoFacade(List.of(provider), flows, session, proof, bindings, mapper, identities, audit);
        var settings = new SsoSettings(SsoSettingsTest.configured("feishu"), new MockEnvironment());
        var started = new SsoFlows(mock(SsoFlowStore.class), settings, Clock.systemUTC()).start("feishu", "LOGIN",
                null, null, null, null);
        f = started.flow();
        browser = started.browser();
        f.setIdentity(SsoFlowsTest.identity());
        when(flows.consume(eq(f), any())).thenReturn(f).thenThrow(SsoException.invalid());
        when(mapper.findExternalIdentity(anyString(), anyString())).thenReturn(new ExternalIdentityRecord(1L, 7L, "p"
                , "s", true));
        when(mapper.findMustChangePasswordById(7)).thenReturn(false);
        when(identities.get(7)).thenReturn(new IdentitySummary(7L, "alice", "Alice", 2L, (short) 1, true, 1L));
        when(session.issue(7)).thenReturn("new-token");
    }

    @Test
    void tokenOnlyReturnedAfterAuditAndResultCannotBeUsedAgain() {
        var result = (LoginResult) facade.exchange(f, browser, "result", null);
        assertThat(result.getLoginId()).isEqualTo(7);
        assertThat(result.getTokenValue()).isEqualTo("new-token");
        assertThat(result.getTokenName()).isEqualTo("satoken");
        assertThat(result.getUser().username()).isEqualTo("alice");
        var order = inOrder(flows, proof, session, audit);
        order.verify(flows).consume(f, "result");
        order.verify(proof).current(7, true);
        order.verify(session).issue(7);
        order.verify(audit).terminal(f.getFlowId(), "LOGIN", "feishu", 7L, true, "OK");
        assertThatThrownBy(() -> facade.exchange(f, browser, "result", null)).isInstanceOf(SsoException.class);
        verify(session).issue(7);
    }

    @Test
    void unboundOrRemovedBindingNeverIssuesSessionAndHasNullTarget() {
        when(mapper.findExternalIdentity(anyString(), anyString())).thenReturn(null);
        assertThatThrownBy(() -> facade.exchange(f, browser, "result", null)).isInstanceOfSatisfying(SsoException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("SSO_BINDING_REQUIRED");
                });
        verifyNoInteractions(session);
        verify(audit).terminal(f.getFlowId(), "LOGIN", "feishu", null, false, "SSO_BINDING_REQUIRED");
    }

    @Test
    void disabledUserRejectsBeforeSessionAndPasswordChangeKeepsRestrictedLoginResult() {
        doThrow(new SsoException(403, "USER_DISABLED")).when(proof).current(7, true);
        assertThatThrownBy(() -> facade.exchange(f, browser, "result", null)).isInstanceOf(SsoException.class);
        verifyNoInteractions(session);
    }

    @Test
    void forcedPasswordFlagIsNotClearedBySso() {
        when(mapper.findMustChangePasswordById(7)).thenReturn(true);
        assertThat(((LoginResult) facade.exchange(f, browser, "result", null)).isMustChangePassword()).isTrue();
        verify(mapper, never()).updatePassword(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void auditFailureRevokesOnlyNewTokenAndCannotRecoverConsumedResult() {
        doThrow(new IllegalStateException("audit down")).when(audit).terminal(anyString(), eq("LOGIN"), eq("feishu"),
                eq(7L), eq(true), anyString());
        assertThatThrownBy(() -> facade.exchange(f, browser, "result", null)).isInstanceOfSatisfying(SsoException.class,
                e -> assertThat(e.code()).isEqualTo("STORAGE_UNAVAILABLE"));
        verify(session).revoke("new-token");
        assertThatThrownBy(() -> facade.exchange(f, browser, "result", null)).isInstanceOf(SsoException.class);
        verify(session).issue(7);
    }

    @Test
    void bindingRequiresOriginalSessionBeforeConsumptionAndCommitsWithoutLogin() {
        f.setIntent("BIND");
        f.setOwnerUserId(7L);
        facade.exchange(f, browser, "result", "original");
        verify(bindings).bind(f);
        verify(session, never()).issue(anyLong());
        verify(audit, never()).terminal(anyString(), anyString(), anyString(), any(), eq(true), anyString());
    }

    @Test
    void invalidBindingSessionDoesNotConsumeAndLocalFailureHasServerEventId() {
        f.setIntent("BIND");
        f.setOwnerUserId(7L);
        doThrow(new SsoException(401, "CREDENTIAL_REJECTED")).when(session).original(f, "expired");
        assertThatThrownBy(() -> facade.exchange(f, browser, "result", "expired")).isInstanceOf(SsoException.class);
        verify(flows, never()).consume(any(), any());
        when(session.require("original")).thenReturn(7L);
        when(proof.verify(7, "wrong")).thenThrow(new SsoException(401, "CREDENTIAL_REJECTED"));
        assertThatThrownBy(() -> facade.start("feishu", browser, "wrong", "original", true)).isInstanceOf(SsoException.class);
        var id = ArgumentCaptor.forClass(String.class);
        verify(audit).terminal(id.capture(), eq("BIND"), eq("feishu"), eq(7L), eq(false), eq("CREDENTIAL_REJECTED"));
        assertThat(id.getValue()).matches("[a-f0-9-]{36}");
        verify(flows, never()).start(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void callbackSuccessAndFailureOnlyPrepareResultNotSessionOrLoginAudit() {
        when(flows.claim(eq("feishu"), eq(browser), eq("state"))).thenReturn(f);
        when(provider.verify(any(), any())).thenThrow(SsoException.denied());
        facade.callback("feishu", browser, Map.of("state", "state", "code", "code"));
        verify(flows).ready(eq(f), isNull(), any(SsoException.class));
        verifyNoInteractions(session, audit, bindings);
    }
}
