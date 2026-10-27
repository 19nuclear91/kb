package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.SecurityLevelMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.LocalCredentialRecord;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创建用户（任务 4.1）：成功、重名、停用部门、字典外密级、重放五个分支，
 * 另加密码强度与用户名长度两条输入面拒绝。
 *
 * <p>全部用 stub mapper，不连库。密码编码器用真实 BCrypt（强度 4 加速）：
 * 成功分支要断言"落库的是初始密码的哈希、审计里没有它"，
 * mock 掉编码器就只剩"调用过一次 encode"这一句空话。
 */
class IdentityServiceCreateUserTest {

    private static final long DEPARTMENT_ID = 2L;
    private static final short CLEARANCE = 1;
    private static final long CREATED_ID = 9L;
    private static final String INITIAL_PASSWORD = "initial-secret-1";

    private IdentityDepartmentMapper mapper;
    private IdentityAuditService audit;
    private SecurityLevelMapper levelMapper;
    private BCryptPasswordEncoder encoder;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        audit = mock(IdentityAuditService.class);
        levelMapper = mock(SecurityLevelMapper.class);
        when(levelMapper.listLevels())
                .thenReturn(List.of((short) 0, (short) 1, (short) 2, (short) 3));
        encoder = new BCryptPasswordEncoder(4);
        service = new IdentityService(mapper, null, audit,
                new SecurityLevelDictionary(levelMapper), encoder, new AuthPasswordProperties(8));
    }

    @Test
    @DisplayName("成功创建：启用、强制改密、初始密码只以哈希落库，审计不含密码")
    void createsEnabledUserWithHashedPasswordAndAuditsWithoutSecrets() {
        when(mapper.findDepartment(DEPARTMENT_ID)).thenReturn(department(true));
        when(mapper.nextIdentityId()).thenReturn(CREATED_ID);
        when(mapper.insertIdentity(any(LocalCredentialRecord.class))).thenReturn(1);
        when(mapper.findIdentity(CREATED_ID)).thenReturn(createdUser());

        IdentitySummary result = service.createUser("alice", "Alice", DEPARTMENT_ID, CLEARANCE,
                INITIAL_PASSWORD, 1L, "req-1");

        assertThat(result.userId()).isEqualTo(CREATED_ID);
        assertThat(result.enabled()).isTrue();
        assertThat(result.version()).isZero();

        ArgumentCaptor<LocalCredentialRecord> inserted =
                ArgumentCaptor.forClass(LocalCredentialRecord.class);
        verify(mapper).insertIdentity(inserted.capture());
        LocalCredentialRecord record = inserted.getValue();
        assertThat(record.getId()).isEqualTo(CREATED_ID);
        assertThat(record.getUsername()).isEqualTo("alice");
        assertThat(record.getDisplayName()).isEqualTo("Alice");
        assertThat(record.getDepartmentId()).isEqualTo(DEPARTMENT_ID);
        assertThat(record.getClearanceLevel()).isEqualTo(CLEARANCE);
        assertThat(record.getMustChangePassword())
                .as("初始凭据必须强制首次修改").isTrue();
        assertThat(encoder.matches(INITIAL_PASSWORD, record.getPasswordHash()))
                .as("落库的必须是初始密码的哈希").isTrue();

        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).success(eq(AuditScope.USER_CREATE), eq("req-1"), eq(1L),
                eq(CREATED_ID), eq("create_user"), eq("{}"), after.capture());
        assertThat(after.getValue())
                .as("审计必须读得出初始密级与强制改密状态")
                .contains("\"clearanceLevel\":1")
                .contains("\"mustChangePassword\":true");
        assertThat(after.getValue())
                .as("审计不得包含密码或哈希")
                .doesNotContain(INITIAL_PASSWORD)
                .doesNotContain(record.getPasswordHash());

        assertThat(service.resolvePrincipal(CREATED_ID).userId())
                .as("创建后立即具备主体解析资格").isEqualTo(CREATED_ID);
    }

    @Test
    @DisplayName("用户名已存在：返回 409，不产生用户")
    void duplicateUsernameReturnsConflictWithoutCreating() {
        when(mapper.findDepartment(DEPARTMENT_ID)).thenReturn(department(true));
        when(mapper.findIdentityByUsername("alice")).thenReturn(createdUserCredential());

        assertThatThrownBy(() -> service.createUser("alice", "Alice", DEPARTMENT_ID, CLEARANCE,
                INITIAL_PASSWORD, 1L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> {
                    IdentityException identity = (IdentityException) e;
                    assertThat(identity.httpStatus()).isEqualTo(409);
                    assertThat(identity.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
                    assertThat(identity.reason()).contains(IdentityConflictReason.IDENTIFIER_CONFLICT);
                });

        verify(mapper, never()).insertIdentity(any());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("唯一约束兜底：文案相同也不推断细分原因，仍为通用 409")
    void databaseConstraintKeepsGenericConflictDespiteSameMessage() {
        when(mapper.findDepartment(DEPARTMENT_ID)).thenReturn(department(true));
        when(mapper.nextIdentityId()).thenReturn(CREATED_ID);
        when(mapper.insertIdentity(any(LocalCredentialRecord.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.createUser("alice", "Alice", DEPARTMENT_ID, CLEARANCE,
                INITIAL_PASSWORD, 1L, "req-1"))
                .isInstanceOfSatisfying(IdentityException.class, identity -> {
                    assertThat(identity.httpStatus()).isEqualTo(409);
                    assertThat(identity.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
                    assertThat(identity.getMessage()).isEqualTo("用户名已存在");
                    assertThat(identity.reason()).isEmpty();
                });
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("目标部门已停用：拒绝创建，不得产生无法解析归属的用户")
    void disabledDepartmentIsRejected() {
        when(mapper.findDepartment(DEPARTMENT_ID)).thenReturn(department(false));

        assertThatThrownBy(() -> service.createUser("alice", "Alice", DEPARTMENT_ID, CLEARANCE,
                INITIAL_PASSWORD, 1L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).httpStatus()).isEqualTo(403))
                .hasMessageContaining("停用");

        verify(mapper, never()).insertIdentity(any());
    }

    @Test
    @DisplayName("密级不在字典中：返回取值错误，不产生用户")
    void levelOutsideDictionaryIsRejected() {
        assertThatThrownBy(() -> service.createUser("alice", "Alice", DEPARTMENT_ID, (short) 9,
                INITIAL_PASSWORD, 1L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).httpStatus()).isEqualTo(400))
                .hasMessageContaining("9");

        verify(mapper, never()).insertIdentity(any());
        verify(mapper, never()).findDepartment(anyLong());
    }

    @Test
    @DisplayName("初始密码不满足强度：拒绝且不落库")
    void weakInitialPasswordIsRejected() {
        assertThatThrownBy(() -> service.createUser("alice", "Alice", DEPARTMENT_ID, CLEARANCE,
                "short", 1L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).httpStatus()).isEqualTo(400))
                .hasMessageContaining("8");

        verify(mapper, never()).insertIdentity(any());
    }

    @Test
    @DisplayName("用户名超长：拒绝且不落库")
    void overlongUsernameIsRejected() {
        String tooLong = "u".repeat(IdentityService.USERNAME_MAX_LENGTH + 1);

        assertThatThrownBy(() -> service.createUser(tooLong, "Alice", DEPARTMENT_ID, CLEARANCE,
                INITIAL_PASSWORD, 1L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).httpStatus()).isEqualTo(400));

        verify(mapper, never()).insertIdentity(any());
    }

    @Test
    @DisplayName("同一请求标识重放：返回首次创建的用户，不再写入")
    void replayedRequestIdReturnsTheFirstCreatedUser() {
        when(audit.existing(AuditScope.USER_CREATE, "req-1"))
                .thenReturn(auditEvent("req-1", CREATED_ID));
        when(mapper.findIdentity(CREATED_ID)).thenReturn(createdUser());

        IdentitySummary result = service.createUser("alice", "Alice", DEPARTMENT_ID, CLEARANCE,
                INITIAL_PASSWORD, 1L, "req-1");

        assertThat(result.userId()).isEqualTo(CREATED_ID);
        assertThat(result.username()).isEqualTo("alice");
        verify(mapper, never()).insertIdentity(any());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    private static DepartmentRecord department(boolean enabled) {
        return new DepartmentRecord(DEPARTMENT_ID, null, "d-2", "Dept", "/2/",
                enabled, 0L, null, null);
    }

    private static IdentityRecord createdUser() {
        return new IdentityRecord(CREATED_ID, "alice", "Alice", DEPARTMENT_ID, CLEARANCE,
                true, 0L, null, null);
    }

    private static LocalCredentialRecord createdUserCredential() {
        LocalCredentialRecord record = new LocalCredentialRecord();
        record.setId(CREATED_ID);
        record.setUsername("alice");
        return record;
    }

    private static IdentityAuditRecord auditEvent(String requestId, long targetId) {
        return new IdentityAuditRecord(null, null, AuditScope.USER_CREATE.name(), requestId,
                1L, "user", targetId, "create_user", "SUCCESS", "{}", "{}", null, null);
    }
}
