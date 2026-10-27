package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.LocalCredentialRecord;
import com.zhishiku.kb.app.auth.model.LoginResult;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.app.config.PasswordEncoderConfig;
import com.zhishiku.kb.common.security.AuthHeaders;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 登录服务的五个分支，外加 2.4 约定的"被节流拒绝不写审计"。
 *
 * <p>密码编码器用真实 BCrypt（强度 4 加速）：假哈希比对是防枚举的关键路径，
 * mock 掉它就测不到"未知用户与密码错耗时相当"的实现了。
 */
class LocalAuthServiceTest {

    private IdentityDepartmentMapper mapper;
    private IdentityService identityService;
    private BCryptPasswordEncoder encoder;
    private LoginThrottle throttle;
    private LoginAuditService audit;
    private LocalAuthService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        identityService = mock(IdentityService.class);
        encoder = spy(new BCryptPasswordEncoder(4));
        throttle = mock(LoginThrottle.class);
        audit = mock(LoginAuditService.class);
        service = new LocalAuthService(mapper, identityService, encoder, throttle, audit,
                mock(IdentityAuditService.class), new AuthPasswordProperties(8));
    }

    private LocalCredentialRecord credential(String hash, boolean mustChange) {
        LocalCredentialRecord record = new LocalCredentialRecord();
        record.setId(7L);
        record.setUsername("alice");
        record.setPasswordHash(hash);
        record.setMustChangePassword(mustChange);
        return record;
    }

    private ResolvedPrincipal principal() {
        return new ResolvedPrincipal(7L, 42L, (short) 1, List.of(), List.of());
    }

    @Test
    void successfulLoginEstablishesSessionAndReturnsExtendedResult() {
        String hash = encoder.encode("correct-horse");
        when(mapper.findIdentityByUsername("alice"))
                .thenReturn(credential(hash, true));
        when(identityService.resolvePrincipal(7L)).thenReturn(principal());
        when(identityService.get(7L))
                .thenReturn(new IdentitySummary(7L, "alice", "Alice", 42L, (short) 1, true, 3L));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("token-abc");

            LoginResult result = service.login("alice", "correct-horse");

            stp.verify(() -> StpUtil.login(7L));
            assertThat(result.getTokenName()).isEqualTo(AuthHeaders.SA_TOKEN);
            assertThat(result.getTokenValue()).isEqualTo("token-abc");
            assertThat(result.getLoginId()).isEqualTo(7L);
            assertThat(result.isMustChangePassword()).isTrue();
            assertThat(result.getUser().username()).isEqualTo("alice");
        }
        verify(throttle).clear("alice");
        verify(audit).success(7L, "alice");
        verify(throttle, never()).recordFailure(anyString());
    }

    @Test
    void wrongPasswordIsRejectedWithUniformUnauthorized() {
        String hash = encoder.encode("correct-horse");
        when(mapper.findIdentityByUsername("alice"))
                .thenReturn(credential(hash, false));

        IdentityException error = catchThrowableOfType(
                () -> service.login("alice", "wrong"), IdentityException.class);

        assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.UNAUTHORIZED);
        assertThat(error.httpStatus()).isEqualTo(401);
        assertThat(error.getMessage()).isEqualTo(LocalAuthService.CREDENTIAL_FAILURE_MESSAGE);
        verify(throttle).recordFailure("alice");
        verify(audit).failure("alice", 7L, "凭据校验失败");
        verifyNoInteractions(identityService);
    }

    @Test
    void unknownUserIsIndistinguishableFromWrongPassword() {
        when(mapper.findIdentityByUsername("ghost")).thenReturn(null);

        IdentityException unknown = catchThrowableOfType(
                () -> service.login("ghost", "whatever"), IdentityException.class);

        verify(encoder).matches("whatever", PasswordEncoderConfig.DUMMY_PASSWORD_HASH);
        verify(throttle).recordFailure("ghost");
        verify(audit).failure("ghost", null, "凭据校验失败");
        verifyNoInteractions(identityService);

        String hash = encoder.encode("correct-horse");
        when(mapper.findIdentityByUsername("alice"))
                .thenReturn(credential(hash, false));
        IdentityException wrong = catchThrowableOfType(
                () -> service.login("alice", "wrong"), IdentityException.class);

        assertThat(unknown.errorCode()).isEqualTo(wrong.errorCode());
        assertThat(unknown.httpStatus()).isEqualTo(wrong.httpStatus());
        assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
    }

    @Test
    void accountWithoutLocalPasswordIsRejectedLikeUnknownUser() {
        when(mapper.findIdentityByUsername("alice")).thenReturn(credential(null, false));

        IdentityException error = catchThrowableOfType(
                () -> service.login("alice", "whatever"), IdentityException.class);

        assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.UNAUTHORIZED);
        verify(encoder).matches("whatever", PasswordEncoderConfig.DUMMY_PASSWORD_HASH);
        verify(throttle).recordFailure("alice");
        verify(audit).failure("alice", 7L, "凭据校验失败");
        verifyNoInteractions(identityService);
    }

    @Test
    void disabledUserAfterCorrectPasswordGets403Disabled() {
        String hash = encoder.encode("correct-horse");
        when(mapper.findIdentityByUsername("alice"))
                .thenReturn(credential(hash, false));
        when(identityService.resolvePrincipal(7L))
                .thenThrow(IdentityException.disabled("用户已停用"));

        IdentityException error = catchThrowableOfType(
                () -> service.login("alice", "correct-horse"), IdentityException.class);

        assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.DISABLED);
        assertThat(error.httpStatus()).isEqualTo(403);
        verify(audit).failure("alice", 7L, "登录被拒绝：DISABLED");
        verify(throttle, never()).recordFailure(anyString());
        verify(throttle, never()).clear(anyString());
    }

    @Test
    void throttledAttemptIsRejectedWithoutAuditOrDatabaseLookup() {
        when(throttle.isThrottled("alice")).thenReturn(true);

        IdentityException error = catchThrowableOfType(
                () -> service.login("alice", "whatever"), IdentityException.class);

        assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.UNAUTHORIZED);
        assertThat(error.getMessage()).isEqualTo(LocalAuthService.CREDENTIAL_FAILURE_MESSAGE);
        verifyNoInteractions(audit);
        verifyNoInteractions(mapper);
        verify(throttle, never()).recordFailure(anyString());
    }
}
