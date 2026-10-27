package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.ExternalIdentityRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.LocalCredentialRecord;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 身份与部门生命周期操作的持久化边界。
 */
@Mapper
public interface IdentityDepartmentMapper {

    /**
     * 所有管理资格收缩路径共用固定键，必须在读取资格事实与取得实体行锁前调用。
     */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(741306011)")
    int lockManagement();

    @Select("""
            SELECT ur.user_id, ur.role_id, rp.permission
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role_permission rp ON rp.role_id = ur.role_id
             WHERE u.enabled = true
               AND rp.permission IN ('identity:role:assign', 'identity:role:write')
            """)
    List<UserRolePermission> findEnabledManagementPermissions();

    @Select("""
            SELECT id, parent_id, code, name, path, enabled, version, created_at, updated_at
              FROM sys_dept WHERE id = #{id}
            """)
    DepartmentRecord findDepartment(@Param("id") long id);

    @Select("""
            SELECT id, parent_id, code, name, path, enabled, version, created_at, updated_at
              FROM sys_dept WHERE id = #{id} FOR UPDATE
            """)
    DepartmentRecord findDepartmentForUpdate(@Param("id") long id);

    @Select("""
            SELECT id, parent_id, code, name, path, enabled, version, created_at, updated_at
              FROM sys_dept WHERE code = #{code}
            """)
    DepartmentRecord findDepartmentByCode(@Param("code") String code);

    @Select("""
            SELECT id, parent_id, code, name, path, enabled, version, created_at, updated_at
              FROM sys_dept ORDER BY path, sort_no, id
            """)
    List<DepartmentRecord> listDepartments();

    @Select("SELECT nextval('sys_dept_id_seq')")
    long nextDepartmentId();

    @Insert("""
            INSERT INTO sys_dept (id, parent_id, code, name, path, enabled, version)
            VALUES (#{id}, #{parentId}, #{code}, #{name}, #{path}, #{enabled}, 0)
            """)
    int insertDepartment(DepartmentRecord department);

    @Update("""
            UPDATE sys_dept
               SET name = #{name}, updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int renameDepartment(@Param("id") long id,
                         @Param("name") String name,
                         @Param("version") long version);

    @Update("""
            UPDATE sys_dept
               SET parent_id = #{parentId}, path = #{path}, updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int moveDepartment(@Param("id") long id,
                       @Param("parentId") Long parentId,
                       @Param("path") String path,
                       @Param("version") long version);

    @Update("""
            UPDATE sys_dept
               SET path = replace(path, #{oldPrefix}, #{newPrefix}),
                   updated_at = now(), version = version + 1
             WHERE path LIKE #{oldPrefixLike} AND id <> #{id}
            """)
    int rewriteDescendantPaths(@Param("id") long id,
                               @Param("oldPrefix") String oldPrefix,
                               @Param("newPrefix") String newPrefix,
                               @Param("oldPrefixLike") String oldPrefixLike);

    @Update("""
            UPDATE sys_dept
               SET enabled = #{enabled}, updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int setDepartmentEnabled(@Param("id") long id,
                             @Param("enabled") boolean enabled,
                             @Param("version") long version);

    @Select("SELECT count(*) FROM sys_dept WHERE parent_id = #{id} AND enabled = true")
    long countEnabledChildren(@Param("id") long id);

    @Select("SELECT count(*) FROM sys_user WHERE dept_id = #{id} AND enabled = true")
    long countEnabledUsers(@Param("id") long id);

    @Select("""
            SELECT id, username, display_name, dept_id, clearance_level, enabled, version, created_at, updated_at
              FROM sys_user WHERE id = #{id}
            """)
    IdentityRecord findIdentity(@Param("id") long id);

    @Select("""
            SELECT id, username, display_name, dept_id, clearance_level, enabled, version, created_at, updated_at
              FROM sys_user ORDER BY id
            """)
    List<IdentityRecord> listIdentities();

    @Select("""
            SELECT id, username, display_name, dept_id, clearance_level, enabled, version, created_at, updated_at
              FROM sys_user WHERE id = #{id} FOR UPDATE
            """)
    IdentityRecord findIdentityForUpdate(@Param("id") long id);

    @Update("""
            UPDATE sys_user
               SET dept_id = #{departmentId}, updated_at = now(), version = version + 1
             WHERE id = #{userId} AND version = #{version}
            """)
    int moveUserToDepartment(@Param("userId") long userId,
                             @Param("departmentId") long departmentId,
                             @Param("version") long version);

    @Update("""
            UPDATE sys_user
               SET enabled = #{enabled}, updated_at = now(), version = version + 1
             WHERE id = #{userId} AND version = #{version}
            """)
    int setUserEnabled(@Param("userId") long userId,
                       @Param("enabled") boolean enabled,
                       @Param("version") long version);

    /**
     * 调整用户密级，受 version 乐观锁保护。
     *
     * <p>{@code AND version = #{version}} 不是可选项。密级是检索侧那条"与"关系里
     * 唯一能凭一次写操作放宽可见范围的因子：少了它，一次基于过期版本的提交会把
     * 另一次已提交的降级静默覆盖回去，于是本该收紧的账户仍然看得见高密级文档，
     * 而两条审计都显示成功。调用方据返回行数为 0 判定并发冲突。
     *
     * <p>WHERE 里<b>刻意不加</b> {@code enabled = true}：停用账户的降级必须可执行
     * （那是收紧方向），而若加上过滤，停用目标会返回 0 行并被上层误报成版本冲突。
     * 目标是否启用由服务层按语义判断，不混进乐观锁的行数语义里。
     */
    @Update("""
            UPDATE sys_user
               SET clearance_level = #{clearanceLevel}, updated_at = now(), version = version + 1
             WHERE id = #{userId} AND version = #{version}
            """)
    int updateUserClearance(@Param("userId") long userId,
                            @Param("clearanceLevel") short clearanceLevel,
                            @Param("version") long version);

    // ------------------------------------------------------------------
    // 本地凭据（V12）。与身份生命周期分开成组：这些语句不参与 version 乐观锁，
    // 也不写身份审计——密码变更的审计由服务层单独记录。
    // ------------------------------------------------------------------

    @Select("""
            SELECT id, username, display_name, dept_id, clearance_level,
                   password_hash, must_change_password
              FROM sys_user WHERE username = #{username}
            """)
    LocalCredentialRecord findIdentityByUsername(@Param("username") String username);

    @Select("SELECT nextval('sys_user_id_seq')")
    long nextIdentityId();

    /**
     * 建号插入。enabled 恒为 true（设计 D9），version 交给数据库默认值 0；
     * must_change_password 由服务层决定，建号与引导都传 true。
     */
    @Insert("""
            INSERT INTO sys_user (id, username, display_name, dept_id, clearance_level,
                                  enabled, password_hash, password_updated_at, must_change_password)
            VALUES (#{id}, #{username}, #{displayName}, #{departmentId}, #{clearanceLevel},
                    true, #{passwordHash}, now(), #{mustChangePassword})
            """)
    int insertIdentity(LocalCredentialRecord identity);

    /**
     * 更新密码哈希与时间戳，并按参数设置或清除强制改密标记。
     *
     * <p>刻意<b>不</b>提升 version（设计 D2）：version 只承载归属、启停、密级
     * 三类身份事实，密码与它们互不冲突；提升会把互不相关的写操作耦合出误报的
     * 版本冲突。代价是密码侧没有乐观锁，由审计幂等兜底。
     */
    @Update("""
            UPDATE sys_user
               SET password_hash = #{passwordHash},
                   password_updated_at = now(),
                   must_change_password = #{mustChangePassword},
                   updated_at = now()
             WHERE id = #{id}
            """)
    int updatePassword(@Param("id") long id,
                       @Param("passwordHash") String passwordHash,
                       @Param("mustChangePassword") boolean mustChangePassword);

    /**
     * 读取强制改密标记：会话查询与强制改密拦截器（设计 D6）共用。
     */
    @Select("SELECT must_change_password FROM sys_user WHERE id = #{id}")
    Boolean findMustChangePasswordById(@Param("id") long userId);

    /**
     * 读取当前密码哈希：自助改密的原密码校验（任务 3.2）用。
     */
    @Select("SELECT password_hash FROM sys_user WHERE id = #{id}")
    String findPasswordHashById(@Param("id") long userId);

    @Select("""
            SELECT id, user_id, provider, subject, enabled
              FROM sys_user_identity
             WHERE provider = #{provider} AND subject = #{subject}
            """)
    ExternalIdentityRecord findExternalIdentity(@Param("provider") String provider,
                                                @Param("subject") String subject);

    @Select("""
            SELECT id, user_id, provider, subject, enabled
              FROM sys_user_identity WHERE id = #{id}
            """)
    ExternalIdentityRecord findExternalIdentityById(@Param("id") long id);

    @Select("""
            SELECT id, user_id, provider, subject, enabled
              FROM sys_user_identity
             WHERE user_id = #{userId} AND provider = #{provider}
             ORDER BY id
            """)
    List<ExternalIdentityRecord> listExternalIdentities(@Param("userId") long userId,
                                                        @Param("provider") String provider);

    @Insert("""
            INSERT INTO sys_user_identity (user_id, provider, subject, enabled)
            VALUES (#{userId}, #{provider}, #{subject}, true)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertExternalIdentity(ExternalIdentityRecord identity);

    @Update("""
            UPDATE sys_user_identity SET enabled = #{enabled}, updated_at = now()
             WHERE id = #{id}
            """)
    int setExternalIdentityEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    @Delete("DELETE FROM sys_user_identity WHERE id = #{id}")
    int deleteExternalIdentity(@Param("id") long id);

    // ------------------------------------------------------------------
    // 角色：目录查询与用户角色绑定。角色取值只来自 sys_role，调用方不得声明。
    // ------------------------------------------------------------------

    /**
     * 角色目录（任务 4.2）：返回全部角色的标识与名称，供管理面选择与核对。
     */
    @Select("SELECT id, code, name FROM sys_role ORDER BY id")
    List<RoleRecord> findAllRoles();

    /**
     * 按 ID 取角色：分配前校验取值确实来自服务端角色表（任务 4.3）。
     */
    @Select("SELECT id, code, name FROM sys_role WHERE id = #{id}")
    RoleRecord findRoleById(@Param("id") long id);

    /**
     * 多态 ACL 无外键：授权写入持有角色键共享锁，与角色删除行锁互斥。
     */
    @Select("SELECT id, code, name FROM sys_role WHERE id = #{id} FOR KEY SHARE")
    RoleRecord findRoleForReference(@Param("id") long id);

    /**
     * 按稳定 code 查询角色，创建和引导使用。
     */
    @Select("SELECT id, code, name FROM sys_role WHERE code = #{code}")
    RoleRecord findRoleByCode(@Param("code") String code);

    @Select("SELECT nextval('sys_role_id_seq')")
    long nextRoleId();

    /**
     * 建角色行（任务 5.2 引导）。code 有唯一约束，并发重复插入由调用方吞并重读。
     */
    @Insert("""
            INSERT INTO sys_role (id, code, name)
            VALUES (#{id}, #{code}, #{name})
            """)
    int insertRole(@Param("id") long id, @Param("code") String code, @Param("name") String name);

    /**
     * 绑定用户与角色（任务 4.3）。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 是幂等语义的落点：重复分配返回 0 行，
     * 调用方据此把它当作成功而不是错误——目标状态已经成立，报错只会制造
     * 无意义的失败。返回 1 表示本次真正新建了绑定。
     */
    @Insert("""
            INSERT INTO sys_user_role (user_id, role_id)
            VALUES (#{userId}, #{roleId})
            ON CONFLICT DO NOTHING
            """)
    int insertUserRole(@Param("userId") long userId, @Param("roleId") long roleId);

    /**
     * 解除用户与角色的绑定（任务 4.3）。返回 0 表示未绑定，调用方据此返回 404：
     * 删除 0 行意味着调用方对当前状态的认知与数据库不一致，不能静默成功。
     */
    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId} AND role_id = #{roleId}")
    int deleteUserRole(@Param("userId") long userId, @Param("roleId") long roleId);

}
