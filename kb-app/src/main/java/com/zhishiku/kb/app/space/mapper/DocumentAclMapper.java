package com.zhishiku.kb.app.space.mapper;

import com.zhishiku.kb.app.space.model.SpaceAclRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档级例外授权的持久化。
 *
 * <p>独立于 {@link SpaceMapper}：例外授权是"空间为主、文档为例外"里的例外一侧，
 * 生命周期跟着文档走（{@code kb_document_acl.doc_id} 对文档级联），
 * 而空间 ACL 跟着空间走。放在一起会让删除空间时的级联语义变得难以推理。
 */
@Mapper
public interface DocumentAclMapper {

    /**
     * 文档当前所属空间。例外授权的判定依据就是这个空间，不是请求里声称的任何一个。
     */
    @Select("""
            SELECT d.space_id FROM kb_document d
             WHERE d.id = #{documentId} AND d.status <> 'deleted'
            """)
    Long findOwningSpaceId(@Param("documentId") long documentId);

    @Select("""
            SELECT count(*) FROM kb_document_acl
             WHERE doc_id = #{documentId}
               AND principal_type = #{principalType}
               AND principal_id = #{principalId}
               AND permission = #{permission}
            """)
    long countAcl(@Param("documentId") long documentId,
                  @Param("principalType") short principalType,
                  @Param("principalId") long principalId,
                  @Param("permission") String permission);

    @Insert("""
            INSERT INTO kb_document_acl (doc_id, principal_type, principal_id, permission)
            VALUES (#{documentId}, #{principalType}, #{principalId}, #{permission})
            ON CONFLICT (doc_id, principal_type, principal_id, permission) DO NOTHING
            """)
    int grantAcl(@Param("documentId") long documentId,
                 @Param("principalType") short principalType,
                 @Param("principalId") long principalId,
                 @Param("permission") String permission);

    @Delete("""
            DELETE FROM kb_document_acl
             WHERE doc_id = #{documentId}
               AND principal_type = #{principalType}
               AND principal_id = #{principalId}
               AND permission = #{permission}
            """)
    int revokeAcl(@Param("documentId") long documentId,
                  @Param("principalType") short principalType,
                  @Param("principalId") long principalId,
                  @Param("permission") String permission);

    @Select("""
            SELECT a.id,
                   a.doc_id AS space_id,
                   a.principal_type,
                   a.principal_id,
                   a.permission,
                   a.principal_key,
                   CASE a.principal_type
                     WHEN 1 THEN (SELECT u.display_name FROM sys_user u WHERE u.id = a.principal_id)
                     WHEN 2 THEN (SELECT r.name FROM sys_role r WHERE r.id = a.principal_id)
                     WHEN 3 THEN (SELECT d.name FROM sys_dept d WHERE d.id = a.principal_id)
                   END AS principal_name
              FROM kb_document_acl a
             WHERE a.doc_id = #{documentId}
             ORDER BY a.principal_type, a.principal_id, a.permission
            """)
    List<SpaceAclRecord> listAcl(@Param("documentId") long documentId);

    @Select("""
            SELECT COALESCE(clearance_level, 0) FROM sys_user WHERE id = #{userId} AND enabled = true
            """)
    Short findUserClearance(@Param("userId") long userId);

    @Select("""
            SELECT count(*) FROM kb_document
             WHERE id = #{documentId} AND security_level > #{clearance}
            """)
    long countDocumentAboveClearance(@Param("documentId") long documentId,
                                     @Param("clearance") short clearance);

    /**
     * 展开部门或角色的有效成员用户 ID，仅用于回执统计密级。
     *
     * <p>口径与空间 ACL 一致：部门按物化路径向下生效，角色取其全部启用成员。
     */
    List<Long> listAclUserIds(@Param("principalType") short principalType,
                              @Param("principalId") long principalId);
}
