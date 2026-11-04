package com.zhishiku.kb.app.space.mapper;

import com.zhishiku.kb.app.space.model.SpaceAclRecord;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 空间生命周期持久化。读取方法都接收已解析的权限范围，不自行拼权限条件。
 */
@Mapper
public interface SpaceMapper {

    /**
     * 同一批量操作域与请求标识在事务内串行化。
     */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE, timeout = 5)
    int lockBatchRequest(@Param("key") String key);

    /**
     * 列出调用者有资格的未归档空间。
     *
     * <p>{@code viewSpaceIds} / {@code manageSpaceIds} 由
     * {@code PermissionService.resolve} 分别按 view 与 manage 解析后传入。
     * {@code globalManager} 为真时给出全部空间，取值 true 时不查 ACL
     * ——这正是"不存在因缺少显式授权而无人可管理的空间"的兜底。
     */
    List<SpaceRecord> listVisible(@Param("viewSpaceIds") List<Long> viewSpaceIds,
                                  @Param("manageSpaceIds") List<Long> manageSpaceIds,
                                  @Param("globalManager") boolean globalManager);

    SpaceRecord findById(@Param("spaceId") long spaceId,
                         @Param("viewSpaceIds") List<Long> viewSpaceIds,
                         @Param("manageSpaceIds") List<Long> manageSpaceIds,
                         @Param("globalManager") boolean globalManager);

    /**
     * 不带权限过滤的原始行，仅供写路径在完成鉴权后读取当前状态。
     */
    @Select("""
            SELECT id, code, name, description, archived, version
              FROM kb_space WHERE id = #{spaceId}
            """)
    SpaceRecord findRawById(@Param("spaceId") long spaceId);

    /**
     * 写路径加行锁，避免同一空间并发更新时乐观锁之外的竞态。
     */
    @Select("""
            SELECT id, code, name, description, archived, version
              FROM kb_space WHERE id = #{spaceId} FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    SpaceRecord findByIdForUpdate(@Param("spaceId") long spaceId);

    @Select("""
            SELECT id, code, name, description, archived, version
              FROM kb_space WHERE code = #{code}
            """)
    SpaceRecord findByCode(@Param("code") String code);

    @Select("SELECT nextval('kb_space_id_seq')")
    long nextSpaceId();

    @Insert("""
            INSERT INTO kb_space (id, code, name, description, archived, version)
            VALUES (#{id}, #{code}, #{name}, #{description}, false, 0)
            """)
    int insertSpace(SpaceRecord space);

    @Insert("INSERT INTO kb_category_tree_state (space_id) VALUES (#{spaceId})")
    void createCategoryTree(@Param("spaceId") long spaceId);

    @Select("SELECT tree_version FROM kb_category_tree_state WHERE space_id = #{spaceId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    long lockCategoryTree(@Param("spaceId") long spaceId);

    @Select("SELECT count(*) FROM kb_document WHERE space_id = #{spaceId}")
    long countReferencedDocuments(@Param("spaceId") long spaceId);

    /**
     * 元数据更新，带乐观锁。
     *
     * <p>归档状态不在此处变更：归档与取消归档是独立语义（见
     * {@link #setArchived}），混进"更新元数据"会让一次改名顺带改变可见性。
     */
    int updateMetadata(@Param("spaceId") long spaceId,
                       @Param("name") String name,
                       @Param("description") String description,
                       @Param("version") long version);

    @Update("""
            UPDATE kb_space
               SET archived = #{archived}, updated_at = now(), version = version + 1
             WHERE id = #{spaceId} AND version = #{version}
            """)
    int setArchived(@Param("spaceId") long spaceId,
                    @Param("archived") boolean archived,
                    @Param("version") long version);

    @Delete("DELETE FROM kb_space WHERE id = #{spaceId}")
    int deleteSpace(@Param("spaceId") long spaceId);

    /**
     * 空间内未被软删除的文档数。
     *
     * <p>删除空间前用它判定冲突。{@code status = 'deleted'} 的文档是软删除残留，
     * 不应让空间永远无法删除；但只要还有活文档，就提示改用归档。
     */
    @Select("""
            SELECT count(*) FROM kb_document
             WHERE space_id = #{spaceId} AND status <> 'deleted'
            """)
    long countLiveDocuments(@Param("spaceId") long spaceId);

    /**
     * 授予 ACL。幂等靠唯一键 (space_id, principal_type, principal_id, permission)。
     */
    @Insert("""
            INSERT INTO kb_space_acl (space_id, principal_type, principal_id, permission)
            VALUES (#{spaceId}, #{principalType}, #{principalId}, #{permission})
            ON CONFLICT (space_id, principal_type, principal_id, permission) DO NOTHING
            """)
    int grantAcl(@Param("spaceId") long spaceId,
                 @Param("principalType") short principalType,
                 @Param("principalId") long principalId,
                 @Param("permission") String permission);

    /**
     * 撤销 ACL。
     *
     * <p>返回影响行数：0 表示本来就不存在该授权。调用方据此决定是否算成功
     * ——撤销一条不存在的授权不改变状态，是幂等的，不应报错。
     */
    int revokeAcl(@Param("spaceId") long spaceId,
                  @Param("principalType") short principalType,
                  @Param("principalId") long principalId,
                  @Param("permission") String permission);

    List<SpaceAclRecord> listAcl(@Param("spaceId") long spaceId);

    /**
     * 判定某条授权是否已存在，用于幂等重试与"重复授予不返回失败"。
     */
    @Select("""
            SELECT count(*) FROM kb_space_acl
             WHERE space_id = #{spaceId}
               AND principal_type = #{principalType}
               AND principal_id = #{principalId}
               AND permission = #{permission}
            """)
    long countAcl(@Param("spaceId") long spaceId,
                  @Param("principalType") short principalType,
                  @Param("principalId") long principalId,
                  @Param("permission") String permission);

    /**
     * 展开一组授权主体键到受影响的有效用户 ID（部门按子树向下生效）。
     *
     * <p>用于授权回执：统计"因密级而当前不生效"的主体数量需要先把主体
     * 落到具体用户上，密级是用户的属性，不是部门的。
     */
    List<Long> expandPrincipalUsers(@Param("spaceId") long spaceId,
                                    @Param("principalKeys") List<String> principalKeys);

    @Select("""
            SELECT COALESCE(clearance_level, 0) FROM sys_user WHERE id = #{userId} AND enabled = true
            """)
    Short findUserClearance(@Param("userId") long userId);

    /**
     * 空间内高于给定密级的 published 文档数，用于判定主体当前是否被密级挡住。
     */
    @Select("""
            SELECT count(*) FROM kb_document
             WHERE space_id = #{spaceId} AND status = 'published'
               AND security_level > #{clearance}
            """)
    long countDocumentsAboveClearance(@Param("spaceId") long spaceId,
                                      @Param("clearance") short clearance);

    /**
     * 展开单个授权主体到其有效用户 ID（部门按子树向下生效，角色取全部成员）。
     */
    List<Long> userIdsForPrincipal(@Param("principalType") short principalType,
                                   @Param("principalId") long principalId);
}
