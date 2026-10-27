package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.model.RoleRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 功能权限事实，与资源 ACL 查询分离。
 */
@Mapper
public interface RolePermissionMapper {
    @Select("SELECT id, code, name FROM sys_role WHERE id = #{roleId} FOR UPDATE")
    RoleRecord lockRole(@Param("roleId") long roleId);

    @Update("UPDATE sys_role SET name = #{name} WHERE id = #{roleId}")
    int renameRole(@Param("roleId") long roleId, @Param("name") String name);

    @Delete("DELETE FROM sys_role WHERE id = #{roleId}")
    int deleteRole(@Param("roleId") long roleId);

    @Select("SELECT count(*) FROM sys_user_role WHERE role_id = #{roleId}")
    long countBoundUsers(@Param("roleId") long roleId);

    @Select("""
            SELECT (SELECT count(*) FROM kb_space_acl WHERE principal_type = 2 AND principal_id = #{roleId})
                 + (SELECT count(*) FROM kb_document_acl WHERE principal_type = 2 AND principal_id = #{roleId})
            """)
    long countAclReferences(@Param("roleId") long roleId);

    @Insert("""
            INSERT INTO sys_role_permission (role_id, permission) VALUES (#{roleId}, #{permission})
            ON CONFLICT DO NOTHING
            """)
    int grantPermission(@Param("roleId") long roleId, @Param("permission") String permission);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId} AND permission = #{permission}")
    int revokePermission(@Param("roleId") long roleId, @Param("permission") String permission);

    @Select("""
            SELECT DISTINCT rp.permission
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role_permission rp ON rp.role_id = ur.role_id
             WHERE u.id = #{userId} AND u.enabled = true
             ORDER BY rp.permission
            """)
    List<String> findUserPermissions(@Param("userId") long userId);

    @Select("SELECT permission FROM sys_role_permission WHERE role_id = #{roleId} ORDER BY permission")
    List<String> findRolePermissions(@Param("roleId") long roleId);

    @Select("SELECT DISTINCT permission FROM sys_role_permission ORDER BY permission")
    List<String> findAllPermissionCodes();

    @Select("""
            SELECT rp.permission FROM sys_role_permission rp
              JOIN sys_role r ON r.id = rp.role_id
             WHERE r.code = #{code} ORDER BY rp.permission
            """)
    List<String> findPermissionsByRoleCode(@Param("code") String code);
}
