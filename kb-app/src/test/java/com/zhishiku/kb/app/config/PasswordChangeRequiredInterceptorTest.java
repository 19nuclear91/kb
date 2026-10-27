package com.zhishiku.kb.app.config;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 强制改密拦截器（任务 3.3）：白名单放行、业务路径拦截、完成改密后放行。
 *
 * <p>不启动容器：直接构造拦截器，用 MockHttpServletRequest 给出方法与路径，
 * mockStatic(StpUtil) 提供会话用户，mapper 用 mock 控制 must_change_password。
 * "完成改密后放行"用同一用户的连续两次请求表达（先 true 后 false）——
 * 这正是每请求读库（设计 D6）要达到的效果：管理员重置后旧会话也立刻被拦。
 */
class PasswordChangeRequiredInterceptorTest {

    private static final long USER_ID = 7L;

    private IdentityDepartmentMapper mapper;
    private PasswordChangeRequiredInterceptor interceptor;

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        interceptor = new PasswordChangeRequiredInterceptor(mapper);
    }

    private boolean preHandle(String method, String path) {
        return interceptor.preHandle(new MockHttpServletRequest(method, path),
                new MockHttpServletResponse(), new Object());
    }

    @Test
    @DisplayName("白名单端点放行且不读库")
    void whitelistedEndpointsPassWithoutDatabaseRead() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertThat(preHandle("POST", "/api/auth/login")).isTrue();
            assertThat(preHandle("POST", "/api/auth/logout")).isTrue();
            assertThat(preHandle("GET", "/api/auth/me")).isTrue();
            assertThat(preHandle("POST", "/api/auth/password")).isTrue();
            assertThat(preHandle("POST", "/api/auth/dev-login")).isTrue();

            verifyNoInteractions(mapper);
            stp.verify(() -> StpUtil.getLoginIdAsLong(), never());
        }
    }

    @Test
    @DisplayName("白名单按方法精确匹配：同路径的其他方法不放行")
    void whitelistMatchesTheMethodNotJustThePath() {
        when(mapper.findMustChangePasswordById(USER_ID)).thenReturn(true);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            IdentityException error = catchThrowableOfType(
                    () -> preHandle("GET", "/api/auth/login"),
                    IdentityException.class);

            assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
        }
    }

    @Test
    @DisplayName("业务路径：标记为真时以 403 PASSWORD_CHANGE_REQUIRED 拒绝")
    void businessPathIsRejectedWhileTheFlagIsSet() {
        when(mapper.findMustChangePasswordById(USER_ID)).thenReturn(true);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            IdentityException error = catchThrowableOfType(
                    () -> preHandle("GET", "/api/space/documents"),
                    IdentityException.class);

            assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
            assertThat(error.httpStatus()).isEqualTo(403);
            verify(mapper).findMustChangePasswordById(USER_ID);
        }
    }

    @Test
    @DisplayName("标记为假放行；同一会话完成改密后立即放行（每请求读库）")
    void passesOnceTheFlagIsClearedEvenForAnExistingSession() {
        when(mapper.findMustChangePasswordById(USER_ID)).thenReturn(true, false);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            catchThrowableOfType(
                    () -> preHandle("GET", "/api/space/documents"),
                    IdentityException.class);

            assertThat(preHandle("GET", "/api/space/documents")).isTrue();
        }
    }

    @Test
    void embeddingReadAndWritePathsRequirePasswordChangeFirst() {
        when(mapper.findMustChangePasswordById(USER_ID)).thenReturn(true);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            for (String[] route : new String[][]{{"GET", "/api/admin/embedding/status"},
                    {"GET", "/api/admin/embedding/profiles"}, {"POST", "/api/admin/embedding/rebuilds"},
                    {"POST", "/api/admin/embedding/rebuilds/run/resume"}}) {
                var error = catchThrowableOfType(() -> preHandle(route[0], route[1]), IdentityException.class);
                assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
            }
        }
    }

    @Test
    void adminQueriesAndNewSelfReadsRemainOutsidePasswordChangeWhitelist() {
        when(mapper.findMustChangePasswordById(USER_ID)).thenReturn(true);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            for (String path : new String[]{"/api/auth/permissions", "/api/auth/security-levels", "/api/admin" +
                    "/documents",
                    "/api/admin/identity/users/search", "/api/admin/spaces/3/acl/principals", "/api/admin/audits"}) {
                var error = catchThrowableOfType(() -> preHandle("GET", path), IdentityException.class);
                assertThat(error.httpStatus()).isEqualTo(403);
                assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
            }
        }
    }
}
