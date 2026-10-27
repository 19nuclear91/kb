package com.zhishiku.kb.app.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限解析相关查询。只读，不含业务写入。
 */
@Mapper
public interface PermissionMapper {

    /**
     * 取用户所在部门及其<b>全部祖先部门</b>的 ID。
     *
     * <p>利用物化路径一次查出，不递归。路径 '/1/2/4/' 拆出 {1,2,4}。
     *
     * <p>为什么是祖先链而不是子树：授权写在「研发中心」上时，平台组的用户
     * 需要命中「研发中心」这个主体，所以要把自己的祖先都算进有效主体集。
     * 语义是"授权向下生效"（见 {@code PrincipalType#DEPT}）。
     */
    @Select("""
            SELECT DISTINCT t.id::bigint
             FROM sys_user u
              JOIN sys_dept d ON d.id = u.dept_id
              CROSS JOIN LATERAL unnest(string_to_array(trim(both '/' from d.path), '/')) AS t(id)
             WHERE u.id = #{userId}
               AND u.enabled = true
               AND d.enabled = true
               AND t.id <> ''
            """)
    List<Long> findDeptChainIds(@Param("userId") long userId);

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> findRoleIds(@Param("userId") long userId);

    /**
     * 为 SaTokenRoleProvider 查询角色 code。ACL 主体仍使用 findRoleIds 的数字 ID；
     * 功能资格由 RolePermissionMapper 解析权限并集。停用过滤在所有事实查询侧执行，
     * 避免旧会话继续得到角色或权限。
     */
    @Select("""
            SELECT r.code
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role r ON r.id = ur.role_id
             WHERE u.id = #{userId}
               AND u.enabled = true
            """)
    List<String> findRoleCodes(@Param("userId") long userId);

    @Select("SELECT COALESCE(clearance_level, 0) FROM sys_user WHERE id = #{userId} AND enabled = true")
    Short findClearanceLevel(@Param("userId") long userId);

    @Select("""
            SELECT u.dept_id
              FROM sys_user u JOIN sys_dept d ON d.id = u.dept_id
             WHERE u.id = #{userId} AND u.enabled = true AND d.enabled = true
            """)
    Long findDepartmentId(@Param("userId") long userId);

    /**
     * 由有效主体反查可见空间。
     *
     * <p>这是粗粒度的主路径：用户通常可见若干个<b>完整</b>空间，
     * 结果集不稀疏——这正是缓解"向量索引强过滤掉召回"的关键设计
     * （见《架构决策记录.md》4.3）。
     *
     * <p>此处过滤空间继承范围；检索和文档 Mapper 的公共访问谓词还必须
     * 独立检查空间归档状态，防止文档例外 ACL 或请求中的旧范围绕过归档。
     *
     * <p>{@code manage} 是<b>例外，不排除归档空间</b>：否则空间一被归档，
     * 被委派的空间管理员连"取消归档"都无法执行，会永久失去该空间
     * （全局管理员仍可兜底，但被委派者不行）。
     */
    @Select("""
            <script>
            SELECT DISTINCT a.space_id
              FROM kb_space_acl a
              JOIN kb_space s ON s.id = a.space_id
             WHERE a.permission = #{permission}
               AND (s.archived = false OR a.permission = 'manage')
               AND a.principal_key IN
                   <foreach item="k" collection="principalKeys" open="(" separator="," close=")">
                     #{k}
                   </foreach>
            </script>
            """)
    List<Long> findVisibleSpaceIds(@Param("principalKeys") List<String> principalKeys,
                                   @Param("permission") String permission);
}
