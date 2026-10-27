package com.zhishiku.kb.worker.embedding.mapper;

import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface EmbeddingLeaseMapper {
    @Select(value = """
            UPDATE kb_embedding_rebuild SET lease_owner = #{owner},
                   lease_until = clock_timestamp() + make_interval(secs => #{seconds}),
                   claim_token = claim_token + 1, updated_at = now()
             WHERE run_id = #{id} AND state = 'REBUILDING' AND targets_ready
               AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp())
               AND (lease_owner IS NULL OR lease_until <= clock_timestamp())
             RETURNING claim_token
            """, affectData = true)
    Long claimRun(@Param("id") UUID id, @Param("owner") UUID owner, @Param("seconds") int seconds);

    @Select(value = """
            UPDATE kb_embedding_rebuild_item i SET state = 'RUNNING', attempts = attempts + 1,
                   total_attempts = total_attempts + 1, claim_token = claim_token + 1,
                   run_claim_token = #{runToken}, lease_owner = #{owner},
                   lease_until = clock_timestamp() + make_interval(secs => #{seconds}), updated_at = now()
             WHERE (i.run_id, i.chunk_id) IN (
                   SELECT c.run_id, c.chunk_id FROM kb_embedding_rebuild_item c
                    WHERE c.run_id = #{id}
                      AND (c.next_attempt_at IS NULL OR c.next_attempt_at <= clock_timestamp())
                      AND (c.state = 'PENDING' OR (c.state = 'RUNNING'
                           AND (c.lease_until <= clock_timestamp() OR c.run_claim_token <> #{runToken})))
                    ORDER BY c.chunk_id LIMIT #{limit} FOR UPDATE SKIP LOCKED)
             RETURNING chunk_id, doc_id, version_id, content_digest, claim_token, attempts
            """, affectData = true)
    List<RebuildBatch.Item> claimItems(@Param("id") UUID id, @Param("runToken") long runToken,
                                       @Param("owner") UUID owner, @Param("seconds") int seconds,
                                       @Param("limit") int limit);

    @Update("""
            UPDATE kb_embedding_rebuild SET lease_until = clock_timestamp() + make_interval(secs => #{seconds})
             WHERE run_id = #{b.runId} AND state = 'REBUILDING' AND claim_token = #{b.runToken}
               AND lease_owner = #{b.owner} AND lease_until > clock_timestamp()
            """)
    int renewRun(@Param("b") RebuildBatch batch, @Param("seconds") int seconds);

    @Update("""
            UPDATE kb_embedding_rebuild_item SET lease_until = clock_timestamp() + make_interval(secs => #{seconds})
             WHERE run_id = #{b.runId} AND chunk_id = #{i.chunkId} AND state = 'RUNNING'
               AND claim_token = #{i.claimToken} AND run_claim_token = #{b.runToken}
               AND lease_owner = #{b.owner} AND lease_until > clock_timestamp()
            """)
    int renewItem(@Param("b") RebuildBatch batch, @Param("i") RebuildBatch.Item item, @Param("seconds") int seconds);

    @Select("""
            SELECT count(*) FROM kb_embedding_rebuild WHERE run_id = #{b.runId} AND state = 'REBUILDING'
               AND claim_token = #{b.runToken} AND lease_owner = #{b.owner} AND lease_until > clock_timestamp()
            """)
    int validRun(@Param("b") RebuildBatch batch);

    @Update("""
            UPDATE kb_embedding_rebuild SET lease_owner = NULL, lease_until = NULL, updated_at = now()
             WHERE run_id = #{b.runId} AND claim_token = #{b.runToken} AND lease_owner = #{b.owner}
               AND state = 'REBUILDING' AND lease_until > clock_timestamp()
            """)
    int release(@Param("b") RebuildBatch batch);
}
