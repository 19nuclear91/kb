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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 登录服务的五个分支，外加 2.4 约定的"被节流拒绝不写审计"；每个分支分别在
 * 来源未知（null）与来源已知时执行，另覆盖来源节流的拒绝顺序。
 *
 * <p>密码编码器用真实 BCrypt（强度 4 加速）：假哈希比对是防枚举的关键路径，
 * mock 掉它就测不到"未知用户与密码错耗时相当"的实现了。
 */
class LocalAuthServiceTest {

    private static final String SOURCE = "10.0.0.1";

    private IdentityDepartmentMapper mapper;
    private IdentityService identityService;
    private BCryptPasswordEncoder encoder;
    private LoginThrottle throttle;
    private LoginAuditService audit;
    private LocalAuthService service;
    private final EpochSessionService sessions = mock(EpochSessionService.class);

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        identityService = mock(IdentityService.class);
        encoder = spy(new BCryptPasswordEncoder(4));
        throttle = mock(LoginThrottle.class);
        audit = mock(LoginAuditService.class);
        service = new LocalAuthService(mapper, identityService, encoder, throttle, audit,
                mock(IdentityAuditService.class), new AuthPasswordProperties(8), sessions);
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

    private void verifySourceFailure(String source) {
        if (source == null) {
            verify(throttle, never()).recordSourceFailure(any());
        } else {
            verify(throttle).recordSourceFailure(source);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = SOURCE)
    void successfulLoginEstablishesSessionAndReturnsExtendedResult(String source) {
        String hash = encoder.encode("correct-horse");
        when(mapper.findIdentityByUsername("alice"))
                .thenReturn(credential(hash, true));
        when(identityService.resolvePrincipal(7L)).thenReturn(principal());
        when(identityService.get(7L))
                .thenReturn(new IdentitySummary(7L, "alice", "Alice", 42L, (short) 1, true, 3L));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            when(sessions.issue(7L, EpochSessionService.Method.LOCAL)).thenReturn("token-abc");

            LoginResult result = service.login("alice", "correct-horse", source);

            verify(sessions).issue(7L, EpochSessionService.Method.LOCAL);
            stp.verifyNoInteractions();
            assertThat(result.getTokenName()).isEqualTo(AuthHeaders.SA_TOKEN);
            assertThat(result.getTokenValue()).isEqualTo("token-abc");
            assertThat(result.getLoginId()).isEqualTo(7L);
            assertThat(result.isMustChangePassword()).isTrue();
            assertThat(result.getUser().username()).isEqualTo("alice");
        }
        // 成功只清账户计数；来源计数没有清除入口，也不应被再计一次失败
        verify(throttle).clear("alice");
        verify(audit).success(7L, "alice");
        verify(throttle, never()).recordFailure(anyString());
        verify(throttle, never()).recordSourceFailure(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = SOURCE)
    void wrongPasswordIsRejectedWithUniformUnauthorized(String source) {
        String hash = encoder.encode("correct-horse");
        when(mapper.findIdentityByUsername("alice"))
                .thenReturn(credential(hash, false));

        IdentityException error = catchThrowableOfType(
                () -> service.login("alice", "wrong", source), IdentityException.class);

        assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.UNAUTHORIZED);
        assertThat(error.httpStatus()).isEqualTo(401);
        assertThat(error.getMessage()).isEqualTo(LocalAuthService.CREDENTIAL_FAILURE_MESSAGE);
        verify(throttle).recordFailure("alice");
        verifySourceFailure(source);
        verify(audit).failure("alice", 7L, "凭据校验失败", source);
        verifyNoInteractions(identityService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = SOURCE)
    void unknownUserIsIndistinguishableFromWrongPassword(String source) {
        when(mapper.findIdentityByUsername("ghost")).thenReturn(null);

        IdentityException unknown = catchThrowableOfType(
                () -> service.login("ghost", "whatever", source), IdentityException.class);

        verify(encoder).matches("whatever", PasswordEncoderConfig.DUMMY_PASSWORD_HASH);
        verify(throttle).recordFailure("ghost");
        verifySourceFailure(source);
        verify(audit).failure("ghost", null, "凭据校验失败", source);
        verifyNoInteractions(identityService);

        String hash = encoder.encode("correct-horse");
        when(mapper.findIdentityByUsername("alice"))
                .thenReturn(credential(hash, false));
        IdentityException wrong = catchThrowableOfType(
                () -> service.login("alice", "wrong", source), IdentityException.class);

        assertThat(unknown.errorCode()).isEqualTo(wrong.errorCode());
        assertThat(unknown.httpStatus()).isEqualTo(wrong.httpStatus());
        assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = SOURCE)
    void accountWithoutLocalPasswordIsRejectedLikeUnknownUser(String source) {
        when(mapper.findIdentityByUsername("alice")).thenReturn(credential(null, false));

        IdentityException error = catchThrowableOfType(
                () -> service.login("alice", "whatever", source), IdentityException.class);

        assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.UNAUTHORIZED);
        verify(encoder).matches("whatever", PasswordEncoderConfig.DUMMY_PASSWORD_HASH);
        verify(throttle).recordFailure("alice");
        verifySourceFailure(source);
        verify(audit).failure("alice", 7L, "凭据校验失败", source);
        verifyNoInteractions(identityService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = SOURCE)
    void disabledUserAfterCorrectPasswordGets403Disabled(String source) {
        String hash = encoder.encode("correct-horse");
        when(mapper.findIdentityByUsername("alice"))
                .thenReturn(credential(hash, false));
        when(identityService.resolvePrincipal(7L))
                .thenThrow(IdentityException.disabled("用户已停用"));

        IdentityException error = catchThrowableOfType(
                () -> service.login("alice", "correct-horse", source), IdentityException.class);

        assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.DISABLED);
        assertThat(error.httpStatus()).isEqualTo(403);
        verify(audit).failure("alice", 7L, "登录被拒绝：DISABLED", source);
        verify(throttle, never()).recordFailure(anyString());
        verify(throttle, never()).recordSourceFailure(any());
        verify(throttle, never()).clear(anyString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = SOURCE)
    void throttledAttemptIsRejectedWithoutAuditOrDatabaseLookup(String source) {
        when(throttle.isThrottled("alice")).thenReturn(true);

        IdentityException error = catchThrowableOfType(
                () -> service.login("alice", "whatever", source), IdentityException.class);

        assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.UNAUTHORIZED);
        assertThat(error.getMessage()).isEqualTo(LocalAuthService.CREDENTIAL_FAILURE_MESSAGE);
        verifyNoInteractions(audit);
        verifyNoInteractions(mapper);
        verify(throttle, never()).recordFailure(anyString());
        verify(throttle, never()).recordSourceFailure(any());
    }

    @Test
    void sourceThrottledAttemptIsRejectedBeforeAccountLookupOrBcrypt() {
        when(throttle.isSourceThrottled(SOURCE)).thenReturn(true);

        // 凭据是否正确无关紧要：拒绝发生在查账户和 bcrypt 之前
        IdentityException error = catchThrowableOfType(
                () -> service.login("alice", "correct-horse", SOURCE), IdentityException.class);

        assertThat(error.errorCode()).isEqualTo(IdentityErrorCode.UNAUTHORIZED);
        assertThat(error.httpStatus()).isEqualTo(401);
        assertThat(error.getMessage()).isEqualTo(LocalAuthService.CREDENTIAL_FAILURE_MESSAGE);
        verify(throttle).isSourceThrottled(SOURCE);
        verifyNoMoreInteractions(throttle);
        verifyNoInteractions(mapper, encoder, audit, identityService, sessions);
    }

    @Test
    void sourceCheckPrecedesAccountCheckWhichPrecedesCredentialLookup() {
        when(mapper.findIdentityByUsername("alice")).thenReturn(null);

        catchThrowableOfType(() -> service.login("alice", "whatever", SOURCE), IdentityException.class);

        InOrder order = inOrder(throttle, mapper);
        order.verify(throttle).isSourceThrottled(SOURCE);
        order.verify(throttle).isThrottled("alice");
        order.verify(mapper).findIdentityByUsername("alice");
    }

    @Test
    void unknownSourceNeverTouchesSourceCounters() {
        String hash = encoder.encode("correct-horse");
        when(mapper.findIdentityByUsername("alice")).thenReturn(credential(hash, false));

        catchThrowableOfType(() -> service.login("alice", "wrong", null), IdentityException.class);

        verify(throttle).isThrottled("alice");
        verify(throttle).recordFailure("alice");
        verify(throttle, never()).isSourceThrottled(any());
        verify(throttle, never()).recordSourceFailure(any());
    }
}
