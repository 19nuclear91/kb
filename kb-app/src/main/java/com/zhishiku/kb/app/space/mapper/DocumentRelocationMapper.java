package com.zhishiku.kb.app.space.mapper;

import com.zhishiku.kb.app.space.model.DocumentAclEntry;
import com.zhishiku.kb.app.space.model.SpaceViewPrincipal;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 文档搬迁的持久化。
 *
 * <p>与 {@link DocumentAclMapper} 并列而不是合入其中：例外授权的 SQL 是"按主体一条条
 * 增删"，搬迁的 SQL 是"按文档整体推进"。两者混在一个接口里，很容易在复制粘贴时
 * 把搬迁语句的 {@code WHERE} 写成带 {@code principal_*} 的那一套，于是只搬走了一部分。
 *
 * <h2>两条必须守住的 SQL 形状</h2>
 * <ul>
 *   <li><b>取归属必须带 {@code FOR UPDATE}</b>。搬迁与 Worker 的分块发布都写
 *       {@code kb_doc_chunk}，靠的就是这把文档行锁串行化；不加锁时发布可以在搬迁的
 *       {@code UPDATE} 之后提交，那批新分块就带着旧 {@code space_id} 落地。</li>
 *   <li><b>清空与同步都按 {@code doc_id} 过滤，不按预读的行集</b>。预读是为了给回执
 *       出摘要，不是为了当作删除/更新的范围：若按预读到的 id 列表删除，在预读之后
 *       才被授出的那条例外会留在原空间的授权遗泽里继续生效。</li>
 * </ul>
 */
@Mapper
public interface DocumentRelocationMapper {

    /**
     * 取文档当前所属空间并锁住该文档行。
     *
     * <p>锁的范围是 {@code kb_document} 的一行，持有到事务结束。{@code ChunkPublisher}
     * 在写分块之前取的是同一把锁，两者因此只能一前一后：发布在后则
     * {@code INSERT ... SELECT d.space_id} 读到新归属，搬迁在后则它按 {@code doc_id}
     * 覆盖发布刚提交的那批行。两条次序都收敛到"分块归属等于文档归属"。
     *
     * @return 当前所属空间；文档不存在或已软删除时为 {@code null}
     */
    @Select("""
            SELECT d.space_id FROM kb_document d
             WHERE d.id = #{documentId} AND d.status <> 'deleted'
             FOR UPDATE
            """)
    Long findOwningSpaceIdForUpdate(@Param("documentId") long documentId);

    /**
     * 列出该文档的全部例外授权，仅取回执摘要需要的两列。
     *
     * <p>只读预览，<b>不</b>作为 {@link #clearAcl(long)} 的删除范围。
     */
    @Select("""
            SELECT a.principal_key AS principalKey, a.permission AS permission
              FROM kb_document_acl a
             WHERE a.doc_id = #{documentId}
             ORDER BY a.principal_type, a.principal_id, a.permission
            """)
    List<DocumentAclEntry> listAclEntries(@Param("documentId") long documentId);

    /**
     * 清空该文档的全部例外授权。
     *
     * <p>按 {@code doc_id} 整体删除，不枚举主体：例外的鉴权依据是"文档当前所属空间的
     * 管理资格"，搬迁让这个依据整体失效，所以没有哪一条例外还站得住。
     *
     * @return 被清除的条数，进回执与审计
     */
    @Delete("DELETE FROM kb_document_acl WHERE doc_id = #{documentId}")
    int clearAcl(@Param("documentId") long documentId);

    /**
     * 推进权威归属。{@code status} 条件挡住与软删除并发的搬迁。
     */
    @Update("""
            UPDATE kb_document
               SET space_id = #{spaceId}, updated_at = now()
             WHERE id = #{documentId} AND status <> 'deleted'
            """)
    int updateDocumentSpace(@Param("documentId") long documentId,
                            @Param("spaceId") long spaceId);

    /**
     * 同步检索用的冗余归属副本。
     *
     * <p>{@code kb_doc_chunk.space_id} 是从 {@code kb_document} 冗余下来、专供
     * {@code SearchMapper.xml} 过滤的副本（见 {@code V2__search_schema.sql} 的注释）。
     * 只改权威值会让文档继续被<b>原空间</b>的人搜到，同时对新空间的人搜不到。
     *
     * <p>{@code V2} 的 {@code trg_sync_chunk_denorm} 触发器也会做这件事。这里仍然显式
     * 写一遍，是因为搬迁的正确性不该依赖一个在应用代码里看不见的触发器——它可以被
     * 某次迁移重建时漏掉，而漏掉不报错，只会让人搜到不该搜的东西。两者写入同一个值，
     * 重复执行无副作用。
     *
     * @return 被同步的分块数
     */
    @Update("""
            UPDATE kb_doc_chunk
               SET space_id = #{spaceId}
             WHERE doc_id = #{documentId}
            """)
    int syncChunkSpace(@Param("documentId") long documentId,
                       @Param("spaceId") long spaceId);

    /**
     * 列出在目标空间持有 {@code view} 的主体。
     *
     * <p>回执用：这些主体是搬迁后"本应看到这篇文档"的人，其中密级不足的那些
     * 当下仍看不到。口径与 {@code DocumentAclService.countClearanceBlocked} 一致。
     */
    @Select("""
            SELECT a.principal_type AS principalType, a.principal_id AS principalId
              FROM kb_space_acl a
             WHERE a.space_id = #{spaceId} AND a.permission = 'view'
             ORDER BY a.principal_type, a.principal_id
            """)
    List<SpaceViewPrincipal> listViewPrincipals(@Param("spaceId") long spaceId);
}
