package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.app.config.PasswordChangeRequiredInterceptor;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理员重置密码（任务 4.5）：重置生效、被重置用户的既有会话随后被强制改密
 * 拦截器拦截、重放返回首次结果，另加弱密码与目标不存在两条拒绝路径。
 *
 * <p>"既有会话被拦截"不重新登录、不启动容器：mapper 的 {@code updatePassword}
 * 把 {@code must_change_password} 写进一个 {@link AtomicBoolean}，
 * {@code findMustChangePasswordById} 从同一个布尔读——拦截器与重置服务经同一
 * 列联动，链路是真的，而不是两次各自独立的 stub。拦截器每请求读库（设计 D6）
 * 正是为了让重置立刻作用到用户已经持有的旧会话上。
 */
class IdentityServicePasswordResetTest {

    private static final long USER_ID = 7L;
    private static final long ACTOR = 1L;
    private static final String NEW_PASSWORD = "brand-new-secret";

    private IdentityDepartmentMapper mapper;
    private IdentityAuditService audit;
    private BCryptPasswordEncoder encoder;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        audit = mock(IdentityAuditService.class);
        encoder = new BCryptPasswordEncoder(4);
        service = new IdentityService(mapper, null, audit, mock(SecurityLevelDictionary.class),
                encoder, new AuthPasswordProperties(8));
    }

    @Test
    @DisplayName("重置生效：新哈希落库、强制改密置真、审计记录前后标记、返回摘要")
    void resetStoresNewHashAndForcesChange() {
        when(mapper.findIdentity(USER_ID)).thenReturn(user());
        when(mapper.findMustChangePasswordById(USER_ID)).thenReturn(false);

        IdentitySummary result = service.resetPassword(USER_ID, NEW_PASSWORD, ACTOR, "req-1");

        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        verify(mapper).updatePassword(eq(USER_ID), storedHash.capture(), eq(true));
        assertThat(encoder.matches(NEW_PASSWORD, storedHash.getValue()))
                .as("落库的必须是新密码的哈希").isTrue();

        verify(audit).success(eq(AuditScope.USER_PASSWORD_RESET), eq("req-1"), eq(ACTOR),
                eq(USER_ID), eq("reset_password"),
                eq("{\"mustChangePassword\":false}"), eq("{\"mustChangePassword\":true}"));
        assertThat(result.userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("被重置用户的既有会话：下一次请求即被强制改密拦截器以 403 拒绝")
    void existingSessionsAreRejectedByTheForceChangeInterceptorAfterReset() {
        AtomicBoolean mustChange = new AtomicBoolean(false);
        when(mapper.findIdentity(USER_ID)).thenReturn(user());
        when(mapper.findMustChangePasswordById(USER_ID)).thenAnswer(invocation -> mustChange.get());
        when(mapper.updatePassword(eq(USER_ID), anyString(), anyBoolean())).thenAnswer(invocation -> {
            mustChange.set(invocation.getArgument(2));
            return 1;
        });
        PasswordChangeRequiredInterceptor interceptor =
                new PasswordChangeRequiredInterceptor(mapper);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            assertThat(businessRequest(interceptor))
                    .as("重置前，既有会话在业务路径上正常通行").isTrue();

            service.resetPassword(USER_ID, NEW_PASSWORD, ACTOR, "req-chain");

            IdentityException error = catchThrowableOfType(
                    () -> businessRequest(interceptor), IdentityException.class);
            assertThat(error.errorCode())
                    .as("无需重新登录：同一会话的下一次请求即进入强制改密状态")
                    .isEqualTo(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
            assertThat(error.httpStatus()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("重放：同一请求标识返回首次结果，零写入")
    void replayedRequestReturnsTheFirstResultWithoutWriting() {
        when(audit.existing(AuditScope.USER_PASSWORD_RESET, "req-replay"))
                .thenReturn(auditEvent("req-replay"));
        when(mapper.findIdentity(USER_ID)).thenReturn(user());

        IdentitySummary result = service.resetPassword(USER_ID, NEW_PASSWORD, ACTOR, "req-replay");

        assertThat(result.userId()).isEqualTo(USER_ID);
        verify(mapper, never()).updatePassword(anyLong(), anyString(), anyBoolean());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("新密码不满足强度：拒绝且不触碰任何数据")
    void weakPasswordIsRejectedWithoutTouchingAnyData() {
        IdentityException error = catchThrowableOfType(
                () -> service.resetPassword(USER_ID, "short", ACTOR, "req-weak"),
                IdentityException.class);

        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.getMessage()).contains("8");
        verifyNoInteractions(mapper);
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("目标用户不存在：404 且不写密码")
    void unknownUserIsNotFound() {
        when(mapper.findIdentity(USER_ID)).thenReturn(null);

        IdentityException error = catchThrowableOfType(
                () -> service.resetPassword(USER_ID, NEW_PASSWORD, ACTOR, "req-404"),
                IdentityException.class);

        assertThat(error.httpStatus()).isEqualTo(404);
        verify(mapper, never()).updatePassword(anyLong(), anyString(), anyBoolean());
    }

    private static boolean businessRequest(PasswordChangeRequiredInterceptor interceptor) {
        return interceptor.preHandle(new MockHttpServletRequest("GET", "/api/space/documents"),
                new MockHttpServletResponse(), new Object());
    }

    private static IdentityRecord user() {
        return new IdentityRecord(USER_ID, "bob", "Bob", 2L, (short) 1, true, 0L, null, null);
    }

    private static IdentityAuditRecord auditEvent(String requestId) {
        return new IdentityAuditRecord(null, null, AuditScope.USER_PASSWORD_RESET.name(), requestId,
                ACTOR, "user", USER_ID, "reset_password", "SUCCESS", "{}", "{}", null, null);
    }
}
