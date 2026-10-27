package com.zhishiku.kb.app.auth;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色 code 查询必须连 {@code sys_role} 取 code，并排除已停用用户。
 *
 * <p>守的是 SQL 本身不被改回去。这两条都是改错不会报错、只会悄悄放行的那类条件：
 * <ul>
 *   <li>少了 {@code sys_role} 连接，取到的是数字 ID，功能资格判定永远不命中；</li>
 *   <li>少了 {@code enabled} 过滤，已停用但仍持有 {@code ROLE_ADMIN} 的账户会拿到
 *       全部身份管理端点——{@code IdentityManagementAuthorizer.requireManager()}
 *       只做 {@code checkLogin + checkRole}，不会替它兜住。</li>
 * </ul>
 *
 * <p>做法与 {@code PermissionMapperArchiveFilterTest} 一致：反射读注解里的 SQL
 * 文本断言形状，不连库、不启动容器。
 */
class PermissionMapperRoleCodeTest {

    private static String roleCodeSql() throws Exception {
        Select select = PermissionMapper.class
                .getMethod("findRoleCodes", long.class)
                .getAnnotation(Select.class);
        assertThat(select).as("findRoleCodes 的 @Select 注解").isNotNull();
        return String.join("\n", select.value());
    }

    @Test
    @DisplayName("角色 code 查询必须连 sys_role 取 code，而不是返回角色 ID")
    void roleCodeQueryJoinsRoleTableForCode() throws Exception {
        String sql = roleCodeSql();

        assertThat(sql).contains("JOIN sys_role r ON r.id = ur.role_id");
        assertThat(sql)
                .as("必须选出 code 列；选 role_id 会让功能资格判定永不命中")
                .contains("SELECT r.code");
    }

    @Test
    @DisplayName("已停用用户不得返回任何角色——否则停用账户仍持有管理资格")
    void disabledUsersYieldNoRoles() throws Exception {
        String sql = roleCodeSql();

        assertThat(sql)
                .as("停用过滤必须在角色来源处，requireManager() 不校验 enabled")
                .contains("u.enabled = true");
    }

    @Test
    @DisplayName("按 ID 取角色的旧查询仍保留，ACL 主体键依赖它")
    void numericRoleLookupIsStillAvailable() throws Exception {
        assertThat(PermissionMapper.class.getMethod("findRoleIds", long.class))
                .as("ROLE:<id> 主体键已写进 kb_space_acl 数据形状，不可移除")
                .isNotNull();
    }
}
