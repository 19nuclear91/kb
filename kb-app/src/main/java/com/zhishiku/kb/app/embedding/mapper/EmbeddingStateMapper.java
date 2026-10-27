package com.zhishiku.kb.app.embedding.mapper;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.common.embedding.StoredEmbeddingProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface EmbeddingStateMapper {
    String CONTROL = """
            SELECT c.state, c.active_profile_id, a.fingerprint AS active_fingerprint,
                   c.target_profile_id, t.fingerprint AS target_fingerprint, c.epoch, c.run_id
              FROM kb_embedding_control c
              LEFT JOIN kb_embedding_profile a ON a.profile_id = c.active_profile_id
              LEFT JOIN kb_embedding_profile t ON t.profile_id = c.target_profile_id
             WHERE c.id = 1
            """;

    @Select(CONTROL)
    EmbeddingControl control();

    @Select(CONTROL + " FOR SHARE OF c")
    EmbeddingControl lockShared();

    @Select(CONTROL + " FOR UPDATE OF c")
    EmbeddingControl lockExclusive();

    @Select("SELECT profile_id, fingerprint, dimension FROM kb_embedding_profile WHERE profile_id = #{id}")
    StoredEmbeddingProfile profile(@Param("id") String id);

    @Select("""
            SELECT a.atttypmod FROM pg_attribute a
              JOIN pg_type t ON t.oid = a.atttypid
             WHERE a.attrelid = to_regclass('kb_doc_chunk') AND a.attname = 'embedding'
               AND t.typname = 'vector' AND NOT a.attisdropped
            """)
    Integer vectorDimension();

    @Select("""
            SELECT run_id, target_profile_id, created_by, state, version, resume_phase, claim_token,
                   lease_owner, lease_until, attempts, max_attempts, next_attempt_at,
                   total_count, succeeded_count, skipped_count, error_code
              FROM kb_embedding_rebuild WHERE run_id = #{id}
            """)
    EmbeddingRun run(@Param("id") UUID id);
}
