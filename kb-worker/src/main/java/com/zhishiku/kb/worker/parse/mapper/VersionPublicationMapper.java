package com.zhishiku.kb.worker.parse.mapper;

import com.zhishiku.kb.worker.parse.model.IndexPublicationCommit;
import com.zhishiku.kb.worker.parse.model.WorkerPublicationState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface VersionPublicationMapper {
    @Select("""
            SELECT d.id FROM kb_document d JOIN kb_document_version v ON v.doc_id = d.id
            WHERE v.id = #{versionId} FOR UPDATE OF d
            """)
    Long lockDocumentForVersion(@Param("versionId") long versionId);

    @Select("""
            SELECT d.id AS document_id, v.id AS version_id, v.version_no, cv.version_no AS current_version_no,
                d.status AS document_status, s.archived AS space_archived, v.publication_policy,
                v.publication_protocol, v.publication_evidence, v.uploader_user_id, v.published_at
            FROM kb_document_version v JOIN kb_document d ON d.id = v.doc_id
            JOIN kb_space s ON s.id = d.space_id
            LEFT JOIN kb_document_version cv ON cv.id = d.current_version_id AND cv.doc_id = d.id
            WHERE v.id = #{versionId} FOR UPDATE OF d, v
            """)
    WorkerPublicationState lockState(@Param("versionId") long versionId);

    @Update("""
            UPDATE kb_document_version SET index_status = 'indexed', index_error = NULL,
                parser_name = #{parserName}, parser_version = #{parserVersion}, parse_truncated = #{truncated},
                parse_issue_count = #{issueCount}, indexed_profile_id = #{profileId}, indexed_fingerprint = #{fingerprint},
                indexed_epoch = #{epoch}, indexed_chunk_count = #{chunkCount}, state_version = state_version + 1
            WHERE id = #{versionId} AND publication_evidence = 'NONE'
            """)
    int indexed(IndexPublicationCommit commit);

    @Update("""
            WITH advanced AS (
                UPDATE kb_document d SET current_version_id = v.id, status = 'published', updated_at = now()
                FROM kb_document_version v, kb_space s
                WHERE v.id = #{versionId} AND v.doc_id = d.id AND s.id = d.space_id AND s.archived = FALSE
                    AND d.status NOT IN ('deleted', 'archived') AND v.publication_policy = 'AUTO'
                    AND v.publication_evidence = 'NONE' AND v.index_status = 'indexed'
                    AND v.indexed_profile_id = #{profileId} AND v.indexed_fingerprint = #{fingerprint}
                    AND (d.current_version_id IS NULL OR EXISTS (SELECT 1 FROM kb_document_version old
                        WHERE old.id = d.current_version_id AND old.doc_id = d.id AND old.version_no < v.version_no))
                RETURNING d.id
            )
            UPDATE kb_document_version v SET publication_evidence = 'PUBLISHED', published_at = #{now},
                state_version = state_version + 1
            FROM advanced a WHERE v.id = #{versionId} AND v.doc_id = a.id AND v.publication_evidence = 'NONE'
            """)
    int publishAuto(@Param("versionId") long versionId, @Param("profileId") String profileId,
                     @Param("fingerprint") String fingerprint, @Param("now") Instant now);
}
