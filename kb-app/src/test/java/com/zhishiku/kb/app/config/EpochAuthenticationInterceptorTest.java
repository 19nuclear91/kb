package com.zhishiku.kb.app.config;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EpochAuthenticationInterceptorTest {
    private final EpochSessionService sessions = mock(EpochSessionService.class);
    private final EpochAuthenticationInterceptor interceptor = new EpochAuthenticationInterceptor(sessions);

    @Test
    void directAppRequestUsesTokenInsteadOfForgedIdentityHeaders() {
        var request = new MockHttpServletRequest("GET", "/api/documents/1");
        request.addHeader("X-User-Id", "999");
        var verified = new EpochSessionService.Session(7, 3, EpochSessionService.Method.LOCAL, false);
        when(sessions.check("token")).thenReturn(verified);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("token");
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }
        assertThat(request.getAttribute(EpochAuthenticationInterceptor.VERIFIED)).isSameAs(verified);
        verify(sessions).check("token");
    }

    @Test
    void exactPublicLoginSkipsAuthenticationButOtherMethodsDoNot() {
        assertThat(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/auth/login"),
                new MockHttpServletResponse(), new Object())).isTrue();
        verifyNoInteractions(sessions);
        when(sessions.check(null)).thenThrow(IdentityException.unauthorized("请重新登录"));
        try (var ignored = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest("GET", "/api/auth/login"),
                    new MockHttpServletResponse(), new Object())).isInstanceOf(IdentityException.class);
        }
    }

    @Test
    void RevokedDisabledAndUnknownStatesNeverReachHandler() {
        for (ApiException failure : new ApiException[]{IdentityException.unauthorized("会话已撤销"),
                IdentityException.disabled("已停用"), ApiException.unavailable("身份未知")}) {
            doThrow(failure).when(sessions).check(null);
            try (var ignored = mockStatic(StpUtil.class)) {
                assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest("GET", "/api/documents/1"),
                        new MockHttpServletResponse(), new Object())).isSameAs(failure);
            }
        }
    }

    @Test
    void passwordRestrictionRemainsAfterEpochCheckAndOnlySelfRecoveryIsAllowed() {
        var users = mock(IdentityDepartmentMapper.class);
        var password = new PasswordChangeRequiredInterceptor(users);
        var request = new MockHttpServletRequest("GET", "/api/documents/1");
        request.setAttribute(EpochAuthenticationInterceptor.VERIFIED,
                new EpochSessionService.Session(7, 3, EpochSessionService.Method.LOCAL, true));
        assertThatThrownBy(() -> password.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(IdentityException.class);
        request.setMethod("POST");
        request.setRequestURI("/api/auth/password");
        assertThat(password.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        verifyNoInteractions(users);
    }
}
