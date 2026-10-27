package com.zhishiku.kb.app.auth;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.LocalCredentialRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地凭据语句的 SQL 形状。
 *
 * <p>做法与 {@code PermissionMapperRoleCodeTest}、{@code SecurityLevelSqlShapeTest}
 * 一致：反射读注解里的 SQL 文本，不连库、不启动容器。
 *
 * <p>守的是三条改错不会报错的条件：
 * <ul>
 *   <li>按用户名查询少选 password_hash，登录会把所有人都当未知用户拒绝；
 *       少了 username 条件则会取到任意一行，静默认错人；</li>
 *   <li>建号插入漏掉 must_change_password，初始密码永不强制修改；</li>
 *   <li>改密语句若提升 version，会与并发的归属、启停、密级调整互相误报版本冲突，
 *       而设计 D2 明确 version 只承载身份事实。</li>
 * </ul>
 */
class LocalCredentialSqlShapeTest {

    private static String byUsernameSql() throws Exception {
        Select select = IdentityDepartmentMapper.class
                .getMethod("findIdentityByUsername", String.class)
                .getAnnotation(Select.class);
        assertThat(select).as("findIdentityByUsername 的 @Select 注解").isNotNull();
        return String.join("\n", select.value());
    }

    private static String insertSql() throws Exception {
        Insert insert = IdentityDepartmentMapper.class
                .getMethod("insertIdentity", LocalCredentialRecord.class)
                .getAnnotation(Insert.class);
        assertThat(insert).as("insertIdentity 的 @Insert 注解").isNotNull();
        return String.join("\n", insert.value());
    }

    private static String updatePasswordSql() throws Exception {
        Update update = IdentityDepartmentMapper.class
                .getMethod("updatePassword", long.class, String.class, boolean.class)
                .getAnnotation(Update.class);
        assertThat(update).as("updatePassword 的 @Update 注解").isNotNull();
        return String.join("\n", update.value());
    }

    @Test
    @DisplayName("按用户名查凭据：必须取自 sys_user、按 username 过滤、带出哈希与强制改密标记")
    void byUsernameQueryCarriesCredentials() throws Exception {
        String sql = byUsernameSql();

        assertThat(sql).contains("FROM sys_user").contains("WHERE username = #{username}");
        assertThat(sql)
                .as("少了 password_hash，登录无法校验任何凭据")
                .contains("password_hash");
        assertThat(sql)
                .as("强制改密标记必须随凭据一起取回，否则登录响应会丢掉它")
                .contains("must_change_password");
    }

    @Test
    @DisplayName("建号插入：凭据三列必须显式写入，enabled 恒为 true")
    void insertCarriesCredentialColumns() throws Exception {
        String sql = insertSql();

        assertThat(sql).contains("password_hash")
                .contains("password_updated_at")
                .contains("must_change_password");
        assertThat(sql)
                .as("新用户必须显式启用；缺省 false 会建出无法登录的账号")
                .contains("enabled")
                .contains("true");
    }

    @Test
    @DisplayName("改密语句：刷新哈希与时间戳、按参数设置或清除标记，且不得提升 version")
    void passwordUpdateRefreshesCredentialsWithoutVersionBump() throws Exception {
        String sql = updatePasswordSql();

        assertThat(sql).contains("password_hash = #{passwordHash}")
                .contains("password_updated_at = now()")
                .contains("must_change_password = #{mustChangePassword}")
                .contains("WHERE id = #{id}");
        assertThat(sql)
                .as("设计 D2：version 只承载归属、启停、密级三类身份事实")
                .doesNotContain("version = version + 1");
    }

    @Test
    @DisplayName("强制改密标记查询：取自 sys_user、按主键过滤")
    void mustChangePasswordLookupReadsTheColumnById() throws Exception {
        Select select = IdentityDepartmentMapper.class
                .getMethod("findMustChangePasswordById", long.class)
                .getAnnotation(Select.class);
        assertThat(select).as("findMustChangePasswordById 的 @Select 注解").isNotNull();

        assertThat(String.join("\n", select.value()))
                .contains("must_change_password")
                .contains("FROM sys_user")
                .contains("WHERE id = #{id}");
    }

    @Test
    @DisplayName("密码哈希查询：取自 sys_user、按主键过滤")
    void passwordHashLookupReadsTheColumnById() throws Exception {
        Select select = IdentityDepartmentMapper.class
                .getMethod("findPasswordHashById", long.class)
                .getAnnotation(Select.class);
        assertThat(select).as("findPasswordHashById 的 @Select 注解").isNotNull();

        assertThat(String.join("\n", select.value()))
                .contains("password_hash")
                .contains("FROM sys_user")
                .contains("WHERE id = #{id}");
    }
}
