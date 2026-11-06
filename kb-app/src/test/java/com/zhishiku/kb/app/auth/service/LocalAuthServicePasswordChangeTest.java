package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 自助改密（任务 3.2）的分支：原密码错误、强度不足、成功改密与幂等重放。
 *
 * <p>密码编码器用真实 BCrypt（强度 4 加速）：成功分支要断言"存进去的哈希能校验
 * 新密码、不再校验原密码"，mock 掉它就只剩"调用过一次 encode"这一句空话。
 *
 * <p>审计用 mock：本类只验证服务层以什么入参调用审计，审计落库本身由
 * {@code IdentityAuditService} 的测试覆盖。
 */
class LocalAuthServicePasswordChangeTest {

    private static final long USER_ID = 7L;

    private IdentityDepartmentMapper mapper;
    private IdentityAuditService identityAudit;
    private BCryptPasswordEncoder encoder;
    private LocalAuthService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        identityAudit = mock(IdentityAuditService.class);
        encoder = new BCryptPasswordEncoder(4);
        service = new LocalAuthService(mapper, mock(IdentityService.class), encoder,
                mock(LoginThrottle.class), mock(LoginAuditService.class), identityAudit,
                new AuthPasswordProperties(8), mock(EpochSessionService.class));
    }

    @Test
    @DisplayName("原密码错误：拒绝且不修改任何状态")
    void wrongOldPasswordIsRejectedWithoutAnyWrite() {
        when(mapper.findPasswordHashById(USER_ID)).thenReturn(encoder.encode("correct-horse"));

        IdentityException error = catchThrowableOfType(
                () -> service.changePassword(USER_ID, "wrong", "brand-new-secret", "rid-1"),
                IdentityException.class);

        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.getMessage()).isEqualTo("原密码错误");
        verify(mapper, never()).updatePassword(anyLong(), anyString(), anyBoolean());
        verify(identityAudit, never()).success(any(), anyString(), any(), any(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("新密码不满足强度：拒绝且不修改任何状态")
    void weakNewPasswordIsRejectedWithoutAnyWrite() {
        when(mapper.findPasswordHashById(USER_ID)).thenReturn(encoder.encode("correct-horse"));

        IdentityException error = catchThrowableOfType(
                () -> service.changePassword(USER_ID, "correct-horse", "short", "rid-2"),
                IdentityException.class);

        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.getMessage())
                .as("拒绝文案必须说明约束要求").contains("8");
        verify(mapper, never()).updatePassword(anyLong(), anyString(), anyBoolean());
        verify(identityAudit, never()).success(any(), anyString(), any(), any(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("改密成功：写入新哈希、清除强制改密标记，审计记录前后状态")
    void successfulChangeStoresNewHashAndClearsTheFlag() {
        when(mapper.findPasswordHashById(USER_ID)).thenReturn(encoder.encode("correct-horse"));
        when(mapper.findMustChangePasswordById(USER_ID)).thenReturn(true);

        service.changePassword(USER_ID, "correct-horse", "brand-new-secret", null);

        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        verify(mapper).updatePassword(eq(USER_ID), storedHash.capture(), eq(false));
        assertThat(encoder.matches("brand-new-secret", storedHash.getValue()))
                .as("存储的必须是新密码的哈希").isTrue();
        assertThat(encoder.matches("correct-horse", storedHash.getValue()))
                .as("改密成功后原密码必须失效").isFalse();

        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        verify(identityAudit).success(eq(AuditScope.USER_PASSWORD_CHANGE), requestId.capture(),
                eq(USER_ID), eq(USER_ID), eq("change_password"),
                eq("{\"mustChangePassword\":true}"), eq("{\"mustChangePassword\":false}"));
        assertThat(requestId.getValue())
                .as("未提供请求标识时由服务端生成 UUID")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("客户端请求标识原样用作幂等键")
    void clientRequestIdIsUsedVerbatim() {
        when(mapper.findPasswordHashById(USER_ID)).thenReturn(encoder.encode("correct-horse"));

        service.changePassword(USER_ID, "correct-horse", "brand-new-secret", "rid-abc");

        verify(identityAudit).existing(AuditScope.USER_PASSWORD_CHANGE, "rid-abc");
        verify(identityAudit).success(eq(AuditScope.USER_PASSWORD_CHANGE), eq("rid-abc"),
                eq(USER_ID), eq(USER_ID), eq("change_password"), anyString(), anyString());
    }

    @Test
    @DisplayName("重放：同一请求标识命中既有审计时零写入直接返回")
    void replayOfAClientRequestIdIsANoOp() {
        when(identityAudit.existing(AuditScope.USER_PASSWORD_CHANGE, "rid-replay"))
                .thenReturn(mock(IdentityAuditRecord.class));

        service.changePassword(USER_ID, "correct-horse", "brand-new-secret", "rid-replay");

        verifyNoInteractions(mapper);
        verify(identityAudit, never()).success(any(), anyString(), any(), any(),
                anyString(), anyString(), anyString());
    }
}
