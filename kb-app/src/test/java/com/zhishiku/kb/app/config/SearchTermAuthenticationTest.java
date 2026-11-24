package com.zhishiku.kb.app.config;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.SearchTermEndpoints;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 身份及改密拦截器分别作为被测组件；不将它们组装成请求处理链。 */
class SearchTermAuthenticationTest {
    @Test
    void allManagementMethodsRequireVerifiedSessionBeforeReadingAnyBody() {
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            var sessions = mock(EpochSessionService.class);
            var interceptor = new EpochAuthenticationInterceptor(sessions);
            var request = unreadable(method);
            request.addHeader("X-User-Id", "999");
            try (var token = mockStatic(StpUtil.class)) {
                token.when(StpUtil::getTokenValue).thenReturn("verified-token-input");
                for (ApiException failure : List.of(IdentityException.unauthorized("private-token"),
                        IdentityException.disabled("private-term"), ApiException.unavailable("private-identity"))) {
                    doThrow(failure).when(sessions).check("verified-token-input");
                    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isSameAs(failure);
                    assertThat(request.getAttribute(EpochAuthenticationInterceptor.VERIFIED)).isNull();
                }
            }
        }
    }

    @Test
    void successfulEpochCheckStoresOnlyVerifiedSessionNotClientIdentityHeaders() {
        var sessions = mock(EpochSessionService.class);
        var verified = new EpochSessionService.Session(7, 3, EpochSessionService.Method.LOCAL, false);
        when(sessions.check("token")).thenReturn(verified);
        var request = unreadable("GET");
        request.addHeader("X-User-Id", "999");
        try (var token = mockStatic(StpUtil.class)) {
            token.when(StpUtil::getTokenValue).thenReturn("token");
            assertThat(new EpochAuthenticationInterceptor(sessions).preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }
        assertThat(request.getAttribute(EpochAuthenticationInterceptor.VERIFIED)).isSameAs(verified);
        verify(sessions).check("token");
    }

    @Test
    void managementPathsNeverBypassRequiredPasswordChangeAndValidRecoveryDoesNotReadBody() {
        var mapper = mock(IdentityDepartmentMapper.class);
        var interceptor = new PasswordChangeRequiredInterceptor(mapper);
        for (String method : List.of("GET", "POST", "PUT", "PATCH")) {
            var request = unreadable(method);
            request.setAttribute(EpochAuthenticationInterceptor.VERIFIED,
                    new EpochSessionService.Session(7, 3, EpochSessionService.Method.LOCAL, true));
            assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                    .isInstanceOfSatisfying(IdentityException.class, error -> assertThat(error.httpStatus()).isEqualTo(403));
            request.setAttribute(EpochAuthenticationInterceptor.VERIFIED,
                    new EpochSessionService.Session(7, 3, EpochSessionService.Method.LOCAL, false));
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }
        verifyNoInteractions(mapper);
    }

    private static MockHttpServletRequest unreadable(String method) {
        return new MockHttpServletRequest(method, SearchTermEndpoints.PREFIX + "/terms/9") {
            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("认证阶段不得读取正文");
            }
        };
    }
}
