package com.zhishiku.kb.worker.embedding.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface EmbeddingTargetMapper {
    /**
     * 重建目标扫描返回的分块及其所属文档版本。
     *
     * @param chunkId   分块 ID
     * @param docId     文档 ID
     * @param versionId 文档版本 ID
     * @param content   分块正文
     */
    record Target(long chunkId, long docId, long versionId, String content) {
    }

    @Select("SELECT targets_ready FROM kb_embedding_rebuild WHERE run_id = #{runId} FOR UPDATE")
    Boolean lockReady(@Param("runId") UUID runId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM kb_document d
                 WHERE d.status <> 'deleted' AND d.current_version_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM kb_doc_chunk c WHERE c.doc_id = d.id AND c.version_id = d.current_version_id)
            )
            """)
    boolean missingChunks();

    @Select("""
            SELECT c.id AS chunk_id, c.doc_id, c.version_id, c.content
              FROM kb_doc_chunk c JOIN kb_document d ON d.id = c.doc_id
             WHERE d.status <> 'deleted' AND c.version_id = d.current_version_id
               AND NOT EXISTS (SELECT 1 FROM kb_embedding_rebuild_item i WHERE i.run_id = #{runId} AND i.chunk_id = c.id)
             ORDER BY c.id LIMIT #{limit}
            """)
    List<Target> page(@Param("runId") UUID runId, @Param("limit") int limit);

    @Insert("""
            INSERT INTO kb_embedding_rebuild_item (run_id, chunk_id, doc_id, version_id, content_digest)
            VALUES (#{runId}, #{target.chunkId}, #{target.docId}, #{target.versionId}, #{digest})
            ON CONFLICT (run_id, chunk_id) DO NOTHING
            """)
    int insert(@Param("runId") UUID runId, @Param("target") Target target, @Param("digest") String digest);

    @Update("""
            UPDATE kb_embedding_rebuild SET targets_ready = #{ready},
                   total_count = (SELECT count(*) FROM kb_embedding_rebuild_item WHERE run_id = #{runId}), updated_at = now()
             WHERE run_id = #{runId} AND state = 'REBUILDING'
            """)
    int progress(@Param("runId") UUID runId, @Param("ready") boolean ready);

    @Update("""
            UPDATE kb_embedding_rebuild SET state = 'BLOCKED', resume_phase = 'REBUILDING', version = version + 1,
                   claim_token = claim_token + 1, lease_owner = NULL, lease_until = NULL,
                   error_code = 'MISSING_CHUNKS', updated_at = now()
             WHERE run_id = #{runId} AND state = 'REBUILDING' AND version = #{version}
            """)
    int blockRun(@Param("runId") UUID runId, @Param("version") long version);

    @Update("""
            UPDATE kb_embedding_control SET state = 'BLOCKED', epoch = epoch + 1
             WHERE id = 1 AND run_id = #{runId} AND state = 'REBUILDING' AND epoch = #{epoch}
            """)
    int blockControl(@Param("runId") UUID runId, @Param("epoch") long epoch);

    @Insert("""
            INSERT INTO kb_embedding_operation (operation_id, operation_domain, request_id, input_digest,
                   run_id, receipt, before_state, after_state, epoch)
            VALUES (#{eventId}, 'BLOCKED', #{requestId}, #{digest}, #{runId},
                    jsonb_build_object('errorCode', 'MISSING_CHUNKS'), 'REBUILDING', 'BLOCKED', #{epoch})
            """)
    int blockEvent(@Param("eventId") UUID eventId, @Param("requestId") String requestId,
                   @Param("digest") String digest, @Param("runId") UUID runId, @Param("epoch") long epoch);
}
