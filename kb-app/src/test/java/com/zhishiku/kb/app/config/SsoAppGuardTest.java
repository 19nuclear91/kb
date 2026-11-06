package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.SsoPublicEndpoints;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SsoAppGuardTest {
    @Test
    void sharedPredicateAndApplicationInterceptorOnlyExemptExactPublicMethods() throws Exception {
        var registry = new InterceptorRegistry();
        var sessions = mock(EpochSessionService.class);
        new SaTokenConfig(mock(IdentityDepartmentMapper.class), sessions).addInterceptors(registry);
        Field registrations = InterceptorRegistry.class.getDeclaredField("registrations");
        registrations.setAccessible(true);
        @SuppressWarnings("unchecked") var entries = (List<InterceptorRegistration>) registrations.get(registry);
        Field interceptor = InterceptorRegistration.class.getDeclaredField("interceptor");
        interceptor.setAccessible(true);
        var auth = (EpochAuthenticationInterceptor) interceptor.get(entries.get(1));
        try (var stp = mockStatic(StpUtil.class)) {
            for (String[] route : publicRoutes()) {
                assertThat(SsoPublicEndpoints.matches(route[0], route[1])).isTrue();
                auth.preHandle(new MockHttpServletRequest(route[0], route[1]), new MockHttpServletResponse(),
                        new Object());
            }
            verifyNoInteractions(sessions);
            int protectedCount = 0;
            for (String[] route : protectedRoutes()) {
                assertThat(SsoPublicEndpoints.matches(route[0], route[1])).isFalse();
                auth.preHandle(new MockHttpServletRequest(route[0], route[1]), new MockHttpServletResponse(),
                        new Object());
                protectedCount++;
            }
            verify(sessions, times(protectedCount)).check(null);
        }
    }

    @Test
    void passwordGateKeepsBindingAndBusinessProtectedAndSetsHeadersBeforeRejection() {
        var mapper = mock(IdentityDepartmentMapper.class);
        when(mapper.findMustChangePasswordById(7)).thenReturn(true);
        var password = new PasswordChangeRequiredInterceptor(mapper);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (String[] route : publicRoutes())
                assertThat(password.preHandle(new MockHttpServletRequest(route[0], route[1]),
                        new MockHttpServletResponse(), null)).isTrue();
            for (String[] route : protectedRoutes())
                assertThatThrownBy(() -> password.preHandle(new MockHttpServletRequest(route[0], route[1]),
                        new MockHttpServletResponse(), null))
                        .isInstanceOf(IdentityException.class);
            var response = new MockHttpServletResponse();
            new SsoSecurityHeadersInterceptor().preHandle(new MockHttpServletRequest(), response, null);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        }
    }

    private static String[][] publicRoutes() {
        return new String[][]{{"GET", "/api/auth/sso/providers"}, {"GET", "/api/auth/sso/feishu/callback"},
                {"POST", "/api/auth/sso/dingtalk/login"}, {"POST", "/api/auth/sso/exchange"}};
    }

    private static String[][] protectedRoutes() {
        return new String[][]{{"POST", "/api/auth/sso/feishu/bindings"}, {"GET", "/api/auth/sso/bindings"},
                {"GET", "/api/auth/sso/exchange"}, {"POST", "/api/auth/sso/feishu/callback"},
                {"POST", "/api/auth/sso/providers"}, {"GET", "/api/auth/sso/feishu/callback/extra"},
                {"GET", "/api/auth/sso/admin"}, {"POST", "/api/ai/chat/history"}};
    }
}
