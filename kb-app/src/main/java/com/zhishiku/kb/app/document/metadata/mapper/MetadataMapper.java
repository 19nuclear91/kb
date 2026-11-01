package com.zhishiku.kb.app.document.metadata.mapper;

import com.zhishiku.kb.app.document.metadata.model.CategoryRow;
import com.zhishiku.kb.app.document.metadata.model.MetadataCommandRow;
import com.zhishiku.kb.app.document.metadata.model.TagRow;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MetadataMapper {
    @Select("""
            SELECT id, space_id, title, summary, category_id, metadata_version, security_level, status,
                owner_id, current_version_id, created_at, updated_at FROM kb_document WHERE id = #{id} FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    DocumentEntity lockDocument(@Param("id") long id);

    @Update("""
            UPDATE kb_document SET title = #{title}, summary = #{summary}, category_id = #{categoryId},
                metadata_version = metadata_version + 1, updated_at = clock_timestamp()
            WHERE id = #{id} AND metadata_version = #{metadataVersion} AND metadata_version < 9223372036854775807
                AND status NOT IN ('deleted', 'archived')
            """)
    int updateMetadata(DocumentEntity row);

    @Delete("DELETE FROM kb_document_tag_relation WHERE document_id = #{id}")
    int clearTags(@Param("id") long id);

    @Insert("""
            <script>INSERT INTO kb_document_tag_relation (document_id, tag_id) VALUES
            <foreach collection="tags" item="tag" separator=",">(#{id}, #{tag})</foreach></script>
            """)
    int insertTags(@Param("id") long id, @Param("tags") List<Long> tags);

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int lockRequest(@Param("key") String key);

    @Insert("""
            INSERT INTO kb_document_metadata_command
                (operation_scope, request_id, actor_user_id, target_id, space_id, document_id,
                 expected_version, digest_version, input_digest, receipt)
            VALUES (#{operationScope}, #{requestId}, #{actorUserId}, #{targetId}, #{spaceId}, #{documentId},
                    #{expectedVersion}, #{digestVersion}, #{inputDigest}, CAST(#{receipt} AS jsonb))
            """)
    int insertCommand(MetadataCommandRow row);

    @Select("""
            SELECT id, space_id, title, summary, category_id, metadata_version,
                   security_level, status, owner_id, current_version_id, created_at, updated_at
            FROM kb_document WHERE id = #{id}
            """)
    DocumentEntity document(@Param("id") long id);

    @Select("""
            SELECT id, space_id, parent_id, name, normalized_name, sort_order, created_at, updated_at
            FROM kb_document_category WHERE space_id = #{spaceId} AND id = #{id}
            """)
    CategoryRow category(@Param("spaceId") long spaceId, @Param("id") long id);

    @Select("""
            SELECT id, name, normalized_name, enabled, version, created_at, updated_at
            FROM kb_document_tag WHERE id = #{id}
            """)
    TagRow tag(@Param("id") long id);

    @Select("""
            SELECT operation_scope, request_id, actor_user_id, target_id, space_id, document_id,
                   expected_version, digest_version, input_digest, receipt::text AS receipt, created_at
            FROM kb_document_metadata_command
            WHERE operation_scope = #{scope} AND request_id = #{requestId}
            """)
    MetadataCommandRow command(@Param("scope") String scope, @Param("requestId") String requestId);
}
