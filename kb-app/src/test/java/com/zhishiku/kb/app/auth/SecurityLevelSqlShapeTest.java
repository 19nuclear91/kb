package com.zhishiku.kb.app.auth;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.SecurityLevelMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密级写语句与字典查询的 SQL 形状。
 *
 * <p>做法与 {@code PermissionMapperRoleCodeTest}、{@code PermissionMapperArchiveFilterTest}
 * 一致：反射读注解里的 SQL 文本，不连库、不启动容器。
 *
 * <p>守的是两条改错不会报错的条件：
 * <ul>
 *   <li>密级 UPDATE 少了 {@code version} 条件 → 基于过期版本的提交会静默覆盖一次
 *       已提交的降级，于是本该收紧的账户仍看得见高密级文档，两条审计都显示成功；</li>
 *   <li>字典查询被改回按区间取值（或干脆被绕过）→ 级数重新写死在代码里，
 *       {@code sys_security_level} 增删级别不再生效。</li>
 * </ul>
 */
class SecurityLevelSqlShapeTest {

    private static String clearanceUpdateSql() throws Exception {
        Update update = IdentityDepartmentMapper.class
                .getMethod("updateUserClearance", long.class, short.class, long.class)
                .getAnnotation(Update.class);
        assertThat(update).as("updateUserClearance 的 @Update 注解").isNotNull();
        return String.join("\n", update.value());
    }

    private static String dictionarySql() throws Exception {
        Select select = SecurityLevelMapper.class
                .getMethod("listLevels")
                .getAnnotation(Select.class);
        assertThat(select).as("listLevels 的 @Select 注解").isNotNull();
        return String.join("\n", select.value());
    }

    @Test
    @DisplayName("密级更新的 WHERE 必须带 version 条件，否则并发降级会被静默覆盖")
    void clearanceUpdateIsGuardedByVersion() throws Exception {
        String sql = clearanceUpdateSql();

        assertThat(sql)
                .as("乐观锁条件缺失时更新恒命中 1 行，调用方拿不到并发冲突")
                .contains("WHERE id = #{userId} AND version = #{version}");
        assertThat(sql)
                .as("必须自增 version，否则后续写操作拿着同一版本号仍然命中")
                .contains("version = version + 1");
        assertThat(sql)
                .as("写的必须是用户密级列")
                .contains("UPDATE sys_user")
                .contains("clearance_level = #{clearanceLevel}");
    }

    /**
     * 停用过滤<b>不得</b>出现在密级 UPDATE 的 WHERE 里。
     *
     * <p>这条与 {@code findRoleCodes} 的要求相反，不是疏漏。角色查询里过滤停用是
     * 收紧；而在密级 UPDATE 里过滤停用，会让停用账户的<b>降级</b>（收紧方向）
     * 返回 0 行，被上层按行数语义误报成版本冲突——调用方会以为重试能解决，
     * 实际永远不会成功。目标是否启用属于服务层的语义判断。
     */
    @Test
    @DisplayName("密级更新不得把 enabled 混进 WHERE——会把停用账户的降级误报成版本冲突")
    void clearanceUpdateDoesNotFilterByEnabled() throws Exception {
        assertThat(clearanceUpdateSql())
                .as("行数为 0 必须只意味着版本冲突，不能同时意味着目标已停用")
                .doesNotContain("enabled");
    }

    @Test
    @DisplayName("密级字典查询从 sys_security_level 取全部级别，不含任何级别上下界")
    void dictionaryQueryReadsTheWholeTable() throws Exception {
        String sql = dictionarySql();

        assertThat(sql)
                .as("取值来源必须是字典表")
                .contains("FROM sys_security_level");
        assertThat(sql)
                .as("必须选出 level 列并排序，调用方要用它拼出可用级别清单")
                .contains("SELECT level")
                .contains("ORDER BY level");
        assertThat(sql.replace("sys_security_level", ""))
                .as("""
                        字典查询里出现 BETWEEN / 比较运算 / 字面量级别，等于把级数
                        写回代码。级数仍是待确认的业务问题，本变更的前提是
                        "答案到位时只改 sys_security_level 的行，不改代码"。""")
                .doesNotContain("BETWEEN")
                .doesNotContain("<")
                .doesNotContain(">")
                .doesNotContainPattern("\\d");
    }
}
