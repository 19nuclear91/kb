package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.model.IdentityQueryViews.ExternalBinding;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.PrincipalOption;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.UserLabel;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.RoleSummary;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 与生命周期写入分离的有界身份查询。
 */
@Mapper
public interface IdentityQueryMapper {
    @Select("""
            <script>
            SELECT id AS user_id, username, display_name, dept_id AS department_id, clearance_level, enabled, version
              FROM sys_user WHERE TRUE
              <if test="like != null">AND (username ILIKE #{like} ESCAPE E'\\\\' OR display_name ILIKE #{like} ESCAPE E'\\\\')</if>
              <if test="departmentId != null">AND dept_id = #{departmentId}</if>
              <if test="enabled != null">AND enabled = #{enabled}</if>
              <if test="afterId != null">AND id &gt; #{afterId}</if>
             ORDER BY id ASC LIMIT #{fetch}
            </script>
            """)
    @ConstructorArgs({@Arg(column = "user_id", javaType = long.class), @Arg(column = "username", javaType =
            String.class),
            @Arg(column = "display_name", javaType = String.class), @Arg(column = "department_id", javaType =
            Long.class),
            @Arg(column = "clearance_level", javaType = short.class), @Arg(column = "enabled", javaType =
            boolean.class),
            @Arg(column = "version", javaType = long.class)})
    List<IdentitySummary> users(@Param("like") String like, @Param("departmentId") Long departmentId,
                                @Param("enabled") Boolean enabled, @Param("afterId") Long afterId,
                                @Param("fetch") int fetch);

    @Select("SELECT EXISTS (SELECT 1 FROM sys_user WHERE id = #{userId})")
    boolean userExists(@Param("userId") long userId);

    @Select("""
            <script>
            SELECT r.id AS role_id, r.code, r.name FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
             WHERE ur.user_id = #{userId}
               <if test="afterId != null">AND r.id &gt; #{afterId}</if>
             ORDER BY r.id ASC LIMIT #{fetch}
            </script>
            """)
    @ConstructorArgs({@Arg(column = "role_id", javaType = long.class), @Arg(column = "code", javaType = String.class),
            @Arg(column = "name", javaType = String.class)})
    List<RoleSummary> roles(@Param("userId") long userId, @Param("afterId") Long afterId, @Param("fetch") int fetch);

    @Select("""
            <script>
            SELECT id AS identity_id, user_id, provider, enabled FROM sys_user_identity WHERE user_id = #{userId}
              <if test="provider != null">AND provider = #{provider}</if>
              <if test="afterId != null">AND id &gt; #{afterId}</if>
             ORDER BY id ASC LIMIT #{fetch}
            </script>
            """)
    @ConstructorArgs({@Arg(column = "identity_id", javaType = long.class), @Arg(column = "user_id", javaType =
            long.class),
            @Arg(column = "provider", javaType = String.class), @Arg(column = "enabled", javaType = boolean.class)})
    List<ExternalBinding> external(@Param("userId") long userId, @Param("provider") String provider,
                                   @Param("afterId") Long afterId, @Param("fetch") int fetch);

    @Select("""
            <script>
            <choose>
              <when test="type.name() == 'USER'">
                SELECT 'USER' AS principal_type, u.id AS principal_id, u.display_name AS label
                  FROM sys_user u JOIN sys_dept d ON d.id = u.dept_id
                 WHERE u.enabled = TRUE AND d.enabled = TRUE
                 <if test="like != null">AND u.display_name ILIKE #{like} ESCAPE E'\\\\'</if>
                 <if test="afterId != null">AND u.id &gt; #{afterId}</if>
                 ORDER BY u.id ASC
              </when>
              <when test="type.name() == 'ROLE'">
                SELECT 'ROLE' AS principal_type, id AS principal_id, name AS label FROM sys_role WHERE TRUE
                 <if test="like != null">AND name ILIKE #{like} ESCAPE E'\\\\'</if>
                 <if test="afterId != null">AND id &gt; #{afterId}</if>
                 ORDER BY id ASC
              </when>
              <otherwise>
                SELECT 'DEPT' AS principal_type, id AS principal_id, name AS label FROM sys_dept WHERE enabled = TRUE
                 <if test="like != null">AND name ILIKE #{like} ESCAPE E'\\\\'</if>
                 <if test="afterId != null">AND id &gt; #{afterId}</if>
                 ORDER BY id ASC
              </otherwise>
            </choose> LIMIT #{fetch}
            </script>
            """)
    @ConstructorArgs({@Arg(column = "principal_type", javaType = PrincipalType.class),
            @Arg(column = "principal_id", javaType = long.class), @Arg(column = "label", javaType = String.class)})
    List<PrincipalOption> principals(@Param("type") PrincipalType type, @Param("like") String like,
                                     @Param("afterId") Long afterId, @Param("fetch") int fetch);

    /** 按精确 ID 批量取显示标签；不支持模糊检索，不能当作用户目录。 */
    @Select("""
            <script>
            SELECT id AS user_id, display_name AS label FROM sys_user WHERE
            <choose>
              <when test="ids != null and ids.size() > 0">
                id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>FALSE</otherwise>
            </choose>
            </script>
            """)
    @ConstructorArgs({@Arg(column = "user_id", javaType = long.class), @Arg(column = "label", javaType = String.class)})
    List<UserLabel> labels(@Param("ids") List<Long> ids);
}
