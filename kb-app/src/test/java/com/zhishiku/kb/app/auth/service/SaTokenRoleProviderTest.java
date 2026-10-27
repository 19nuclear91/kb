package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色来源的行为断言。
 *
 * <p>这些用例针对 {@link SaTokenRoleProvider} 本身，因此<b>无法</b>通过 mock
 * {@link IdentityManagementAuthorizer} 满足——那正是原缺陷能在 65 个测试全绿的
 * 情况下存活的原因：所有相关用例都把唯一接触 Sa-Token 的那一层 mock 掉了。
 */
class SaTokenRoleProviderTest {

    private PermissionMapper mapper;
    private SaTokenRoleProvider provider;

    @BeforeEach
    void setUp() {
        mapper = mock(PermissionMapper.class);
        provider = new SaTokenRoleProvider(mapper);
    }

    @Test
    @DisplayName("持有管理角色的用户返回该角色 code")
    void holderGetsRoleCode() {
        when(mapper.findRoleCodes(1L)).thenReturn(List.of("ROLE_ADMIN", "ROLE_AUDITOR"));

        assertThat(provider.getRoleList(1L, "login"))
                .containsExactly("ROLE_ADMIN", "ROLE_AUDITOR");
    }

    @Test
    @DisplayName("撤销关联后不再返回任何角色")
    void revokedUserGetsNoRole() {
        when(mapper.findRoleCodes(1L)).thenReturn(List.of());

        assertThat(provider.getRoleList(1L, "login")).isEmpty();
    }

    @Test
    @DisplayName("角色来源必须是过滤了停用用户的 code 查询，不得改用按 ID 的旧查询")
    void rolesComeFromTheEnabledFilteredCodeQuery() {
        when(mapper.findRoleCodes(7L)).thenReturn(List.of("ROLE_ADMIN"));

        provider.getRoleList(7L, "login");

        // 停用过滤只存在于 findRoleCodes 的 SQL 里（见 PermissionMapperRoleCodeTest）。
        // 改调 findRoleIds 会同时丢掉过滤和 code 语义，且不会有任何编译错误。
        verify(mapper).findRoleCodes(7L);
        verify(mapper, never()).findRoleIds(anyLong());
    }

    @Test
    @DisplayName("会话往返后登录标识是字符串也要能解析")
    void stringLoginIdFromRedisSessionIsParsed() {
        when(mapper.findRoleCodes(42L)).thenReturn(List.of("ROLE_ADMIN"));

        assertThat(provider.getRoleList("42", "login")).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("登录标识无法解析时返回空角色，而不是抛异常")
    void unparsableLoginIdDeniesInsteadOfFailing() {
        assertThat(provider.getRoleList("not-a-user-id", "login")).isEmpty();
        assertThat(provider.getRoleList(null, "login")).isEmpty();

        verify(mapper, never()).findRoleCodes(anyLong());
    }

    @Test
    @DisplayName("mapper 返回 null 时按无角色处理")
    void nullFromMapperIsTreatedAsNoRole() {
        when(mapper.findRoleCodes(1L)).thenReturn(null);

        assertThat(provider.getRoleList(1L, "login")).isEmpty();
    }

    @Test
    @DisplayName("permission 语义未启用，固定返回空列表")
    void permissionListIsEmptyByDesign() {
        assertThat(provider.getPermissionList(1L, "login")).isEmpty();
    }
}
