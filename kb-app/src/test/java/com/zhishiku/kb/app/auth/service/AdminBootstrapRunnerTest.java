package com.zhishiku.kb.app.auth.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.LocalCredentialRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.app.config.BootstrapProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 首个管理员引导（任务 5.2）的六条路径：未配置、弱密码、已有持有者、
 * 空库全建、重复启动零修改、已存在但停用只记 WARN。
 *
 * <p>不启动容器也不连库：mapper 与审计都是 mock，bcrypt 用真实编码器
 * （成本 4）以便断言落库哈希确实匹配配置密码。"重复启动"用
 * {@link AtomicBoolean} 让首次运行的绑定写入被第二次运行的持有者查询读到——
 * 两次运行经同一状态联动，幂等是真的，而不是两次各自独立的 stub。
 *
 * <p>"只记 WARN"通过挂到 logback 的 {@link ListAppender} 断言：该路径的
 * 全部行为就是一条 WARN，只验证"没有写操作"会漏掉这条唯一的行为。
 */
class AdminBootstrapRunnerTest {

    private static final String USERNAME = "root";
    private static final String PASSWORD = "initial-secret-1";

    private IdentityDepartmentMapper mapper;
    private RolePermissionMapper rolePermissions;
    private IdentityAuditService audit;
    private SecurityLevelDictionary securityLevels;
    private BCryptPasswordEncoder encoder;
    private AdminBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        rolePermissions = mock(RolePermissionMapper.class);
        audit = mock(IdentityAuditService.class);
        securityLevels = mock(SecurityLevelDictionary.class);
        encoder = new BCryptPasswordEncoder(4);
        runner = new AdminBootstrapRunner(properties(USERNAME, PASSWORD), mapper, audit,
                securityLevels, encoder, new AuthPasswordProperties(8), rolePermissions);
    }

    @Test
    @DisplayName("未配置凭据：记 INFO 跳过，零动作")
    void unconfiguredCredentialsSkipEverything() {
        runner = new AdminBootstrapRunner(properties("", ""), mapper, audit, securityLevels,
                encoder, new AuthPasswordProperties(8), rolePermissions);
        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            runner.run(null);
        } finally {
            detachLogAppender(logs);
        }

        verifyNoInteractions(mapper);
        verifyNoInteractions(audit);
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.INFO
                && event.getFormattedMessage().contains("跳过"));
    }

    @Test
    @DisplayName("密码不合强度：启动失败且不触碰任何数据")
    void weakPasswordFailsStartupWithoutWrites() {
        runner = new AdminBootstrapRunner(properties(USERNAME, "short"), mapper, audit,
                securityLevels, encoder, new AuthPasswordProperties(8), rolePermissions);

        IllegalStateException error = catchThrowableOfType(() -> runner.run(null),
                IllegalStateException.class);

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("8").contains("KB_BOOTSTRAP_ADMIN_PASSWORD");
        verifyNoInteractions(mapper);
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("已存在启用 ROLE_ADMIN 持有者：零动作零修改")
    void existingEnabledHolderIsZeroAction() {
        when(mapper.findEnabledManagementPermissions())
                .thenReturn(qualifiedUser());

        runner.run(null);

        verifyNoInteractions(rolePermissions);
        verify(mapper, never()).lockManagement();

        verify(mapper).findEnabledManagementPermissions();
        verify(mapper, never()).findIdentityByUsername(anyString());
        verify(mapper, never()).insertRole(anyLong(), anyString(), anyString());
        verify(mapper, never()).insertDepartment(any());
        verify(mapper, never()).insertIdentity(any());
        verify(mapper, never()).insertUserRole(anyLong(), anyLong());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("空库：建出角色行、部门、管理员与绑定，审计 actor 为 null、请求标识互异")
    void emptyDatabaseCreatesRoleDepartmentUserAndBinding() {
        when(mapper.findEnabledManagementPermissions())
                .thenReturn(List.of());
        when(mapper.findIdentityByUsername(USERNAME)).thenReturn(null);
        when(mapper.findRoleByCode("ROLE_ADMIN"))
                .thenReturn(null);
        when(mapper.nextRoleId()).thenReturn(11L);
        when(mapper.insertRole(anyLong(), anyString(), anyString())).thenReturn(1);
        when(mapper.findDepartmentByCode("HQ")).thenReturn(null);
        when(mapper.nextDepartmentId()).thenReturn(21L);
        when(mapper.insertDepartment(any())).thenReturn(1);
        when(mapper.nextIdentityId()).thenReturn(31L);
        when(mapper.insertIdentity(any())).thenReturn(1);
        when(securityLevels.contains((short) 1)).thenReturn(true);
        when(mapper.insertUserRole(anyLong(), anyLong())).thenReturn(1);

        runner.run(null);

        ArgumentCaptor<DepartmentRecord> department =
                ArgumentCaptor.forClass(DepartmentRecord.class);
        verify(mapper).insertDepartment(department.capture());
        assertThat(department.getValue().getPath()).as("根部门物化路径 /<id>/")
                .isEqualTo("/21/");
        assertThat(department.getValue().getParentId()).isNull();
        assertThat(department.getValue().getEnabled()).isTrue();
        assertThat(department.getValue().getCode()).isEqualTo("HQ");

        ArgumentCaptor<LocalCredentialRecord> user =
                ArgumentCaptor.forClass(LocalCredentialRecord.class);
        verify(mapper).insertIdentity(user.capture());
        assertThat(user.getValue().getMustChangePassword())
                .as("引导管理员首次登录必须改密").isTrue();
        assertThat(user.getValue().getDepartmentId()).isEqualTo(21L);
        assertThat(user.getValue().getClearanceLevel()).isEqualTo((short) 1);
        assertThat(encoder.matches(PASSWORD, user.getValue().getPasswordHash()))
                .as("落库的是配置密码的 bcrypt 哈希").isTrue();

        verify(mapper).insertRole(11L, "ROLE_ADMIN",
                "系统管理员");
        verify(mapper).insertUserRole(31L, 11L);
        for (String code : Permission.codes()) {
            verify(rolePermissions).grantPermission(11L, code);
        }
        verify(rolePermissions, times(Permission.codes().size())).grantPermission(eq(11L), anyString());
        verify(rolePermissions).grantPermission(11L, "embedding:read");
        verify(rolePermissions).grantPermission(11L, "embedding:rebuild");
        verify(rolePermissions).grantPermission(11L, "operations:reports:read");
        verify(rolePermissions).grantPermission(11L, "document:tag:read");
        verify(rolePermissions).grantPermission(11L, "document:tag:write");

        ArgumentCaptor<String> requestIds = ArgumentCaptor.forClass(String.class);
        verify(audit, times(Permission.codes().size() + 4)).success(any(), requestIds.capture(), isNull(), any(),
                anyString(), anyString(), anyString());
        assertThat(requestIds.getAllValues())
                .as("服务端为每条审计事件生成独立请求标识")
                .doesNotHaveDuplicates();
        verify(audit).success(eq(AuditScope.ROLE_CREATE), anyString(), isNull(), eq(11L),
                anyString(), anyString(), anyString());
        verify(audit).success(eq(AuditScope.DEPARTMENT_CREATE), anyString(), isNull(), eq(21L),
                anyString(), anyString(), anyString());
        verify(audit).success(eq(AuditScope.USER_ROLE_ASSIGN), anyString(), isNull(), eq(31L),
                anyString(), anyString(), anyString());

        ArgumentCaptor<String> afterState = ArgumentCaptor.forClass(String.class);
        verify(audit).success(eq(AuditScope.USER_CREATE), anyString(), isNull(), eq(31L),
                anyString(), anyString(), afterState.capture());
        assertThat(afterState.getValue())
                .as("审计记录强制改密标记，但不含任何密码或哈希")
                .contains("\"mustChangePassword\":true")
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("重复启动：第二次只见持有者，零修改")
    void repeatedRunMakesNoFurtherWrites() {
        AtomicBoolean adminExists = new AtomicBoolean(false);
        when(mapper.findEnabledManagementPermissions())
                .thenAnswer(invocation -> adminExists.get() ? qualifiedUser() : List.of());
        when(mapper.findIdentityByUsername(USERNAME)).thenReturn(null);
        when(mapper.findRoleByCode("ROLE_ADMIN"))
                .thenReturn(null);
        when(mapper.nextRoleId()).thenReturn(11L);
        when(mapper.findDepartmentByCode("HQ")).thenReturn(null);
        when(mapper.nextDepartmentId()).thenReturn(21L);
        when(mapper.nextIdentityId()).thenReturn(31L);
        when(mapper.insertIdentity(any())).thenReturn(1);
        when(securityLevels.contains((short) 1)).thenReturn(true);
        when(mapper.insertUserRole(anyLong(), anyLong())).thenAnswer(invocation -> {
            adminExists.set(true);
            return 1;
        });

        runner.run(null);
        clearInvocations(mapper, audit);
        runner.run(null);

        verify(mapper).findEnabledManagementPermissions();
        verify(mapper, never()).findIdentityByUsername(anyString());
        verify(mapper, never()).insertRole(anyLong(), anyString(), anyString());
        verify(mapper, never()).insertDepartment(any());
        verify(mapper, never()).insertIdentity(any());
        verify(mapper, never()).insertUserRole(anyLong(), anyLong());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("配置的管理员已存在但被停用：只记 WARN，不重新启用，零写入")
    void existingDisabledAdminLogsWarnWithoutReEnabling() {
        when(mapper.findEnabledManagementPermissions())
                .thenReturn(List.of());
        when(mapper.findIdentityByUsername(USERNAME)).thenReturn(credential(77L));
        when(mapper.findIdentity(77L)).thenReturn(new IdentityRecord(77L, USERNAME, "系统管理员",
                null, (short) 1, false, 0L, null, null));

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            runner.run(null);
        } finally {
            detachLogAppender(logs);
        }

        verify(mapper, never()).findRoleByCode(anyString());
        verify(mapper, never()).findDepartmentByCode(anyString());
        verify(mapper, never()).insertRole(anyLong(), anyString(), anyString());
        verify(mapper, never()).insertDepartment(any());
        verify(mapper, never()).insertIdentity(any());
        verify(mapper, never()).insertUserRole(anyLong(), anyLong());
        verify(mapper, never()).setUserAccessState(anyLong(), anyBoolean(), anyBoolean(), anyLong(), anyLong());
        verify(mapper, never()).updatePassword(anyLong(), anyString(), anyBoolean());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains(USERNAME));
    }

    @Test
    void existingIncompleteAdminRoleWarnsWithoutRepairingPermissions() {
        when(mapper.findRoleByCode("ROLE_ADMIN")).thenReturn(
                new RoleRecord(11L, "ROLE_ADMIN", "系统管理员"));
        when(rolePermissions.findRolePermissions(11L)).thenReturn(
                Permission.codes().stream()
                        .filter(code -> !code.equals("document:security-level:write")
                                && !code.startsWith("embedding:") && !code.equals("audit:read")
                                && !code.equals("identity:external:read") && !code.startsWith("ai:")
                                && !code.startsWith("identity:lifecycle:") && !code.startsWith("identity:emergency:")
                                && !code.equals("operations:reports:read") && !code.startsWith("document:tag:")).toList());
        when(mapper.findIdentityByUsername(USERNAME)).thenReturn(credential(31L));
        when(mapper.findIdentity(31L)).thenReturn(new IdentityRecord(31L, USERNAME, "系统管理员",
                21L, (short) 1, true, 0L, null, null));
        when(mapper.findDepartmentByCode("HQ")).thenReturn(
                new DepartmentRecord(21L, null, "HQ", "总部", "/21/", true, 0L, null, null));
        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            runner.run(null);
            runner.run(null);
        } finally {
            detachLogAppender(logs);
        }
        verify(rolePermissions, never()).grantPermission(anyLong(), anyString());
        verify(rolePermissions, never()).revokePermission(anyLong(), anyString());
        verify(mapper, never()).updateUserClearance(anyLong(), ArgumentMatchers.anyShort(), anyLong());
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("ROLE_ADMIN")
                && event.getFormattedMessage().contains("不自动修复")
                && event.getFormattedMessage().contains("document:security-level:write")
                && event.getFormattedMessage().contains("embedding:read")
                && event.getFormattedMessage().contains("embedding:rebuild")
                && event.getFormattedMessage().contains("operations:reports:read")
                && event.getFormattedMessage().contains("identity:lifecycle:read")
                && event.getFormattedMessage().contains("identity:lifecycle:manage")
                && event.getFormattedMessage().contains("identity:lifecycle:restore")
                && event.getFormattedMessage().contains("identity:emergency:manage"));
    }

    private static List<UserRolePermission> qualifiedUser() {
        return List.of(new UserRolePermission(31L, 11L, "identity:role:assign"),
                new UserRolePermission(31L, 12L, "identity:role:write"));
    }

    private static BootstrapProperties properties(String username, String password) {
        return new BootstrapProperties(
                new BootstrapProperties.Admin(username, password, "系统管理员", (short) 1),
                new BootstrapProperties.Department("HQ", "总部"));
    }

    private static LocalCredentialRecord credential(long id) {
        LocalCredentialRecord record = new LocalCredentialRecord();
        record.setId(id);
        record.setUsername(USERNAME);
        record.setMustChangePassword(false);
        return record;
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runnerLogger().addAppender(appender);
        return appender;
    }

    private static void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        runnerLogger().detachAppender(appender);
    }

    private static Logger runnerLogger() {
        return (Logger) LoggerFactory.getLogger(AdminBootstrapRunner.class);
    }
}
