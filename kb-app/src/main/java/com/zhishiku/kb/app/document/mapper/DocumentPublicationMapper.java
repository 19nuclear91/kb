package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.IndexIntegrity;
import com.zhishiku.kb.common.conversation.SourceKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface DocumentPublicationMapper {
    @Select("""
            SELECT doc_id AS document_id, id AS version_id FROM kb_document_version
            WHERE publication_policy = 'AUTO' AND publication_evidence = 'NONE' AND index_status = 'indexed'
                AND id > #{afterId} ORDER BY id LIMIT #{limit}
            """)
    List<SourceKey> autoCandidates(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select("""
            SELECT count(*) AS chunk_count, count(DISTINCT c.chunk_idx) AS distinct_indexes,
                min(c.chunk_idx) AS first_index, max(c.chunk_idx) AS last_index,
                count(*) FILTER (WHERE c.doc_id = d.id AND c.space_id = d.space_id AND c.security_level = d.security_level
                    AND c.embedding_profile_id = #{profileId} AND c.embedding IS NOT NULL AND vector_dims(c.embedding) = 1024
                    AND length(btrim(c.content)) > 0) AS valid_count
            FROM kb_doc_chunk c JOIN kb_document d ON d.id = #{documentId} WHERE c.version_id = #{versionId}
            """)
    IndexIntegrity integrity(@Param("documentId") long documentId, @Param("versionId") long versionId,
                             @Param("profileId") String profileId);

    @Update("""
            WITH advanced AS (
                UPDATE kb_document d SET current_version_id = v.id, status = 'published', updated_at = now()
                FROM kb_document_version v, kb_space s
                WHERE d.id = #{documentId} AND v.id = #{versionId} AND v.doc_id = d.id AND s.id = d.space_id
                    AND s.archived = FALSE AND d.status NOT IN ('deleted', 'archived')
                    AND v.publication_policy = #{policy} AND v.publication_evidence = 'NONE' AND v.index_status = 'indexed'
                    AND v.state_version = #{expectedVersion} AND v.indexed_profile_id = #{profileId}
                    AND v.indexed_fingerprint = #{fingerprint}
                    AND (d.current_version_id IS NULL OR EXISTS (SELECT 1 FROM kb_document_version old
                        WHERE old.id = d.current_version_id AND old.doc_id = d.id AND old.version_no < v.version_no))
                RETURNING d.id
            )
            UPDATE kb_document_version v SET publication_evidence = 'PUBLISHED', published_at = #{now},
                state_version = state_version + 1
            FROM advanced a WHERE v.id = #{versionId} AND v.doc_id = a.id AND v.state_version = #{expectedVersion}
                AND v.publication_evidence = 'NONE'
            """)
    int publish(@Param("documentId") long documentId, @Param("versionId") long versionId,
                 @Param("expectedVersion") long expectedVersion, @Param("policy") String policy,
                 @Param("profileId") String profileId, @Param("fingerprint") String fingerprint,
                 @Param("now") Instant now);
}
