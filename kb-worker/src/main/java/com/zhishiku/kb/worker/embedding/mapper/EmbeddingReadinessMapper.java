package com.zhishiku.kb.worker.embedding.mapper;

import com.zhishiku.kb.common.embedding.EmbeddingReadiness;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmbeddingReadinessMapper {
    @Insert("""
            INSERT INTO kb_embedding_readiness (instance_id, role, profile_id, fingerprint, protocol_version,
                ready, checked_at, valid_until)
            VALUES (#{instanceId}, #{role}, #{profileId}, #{fingerprint}, #{protocolVersion},
                #{ready}, #{checkedAt}, #{validUntil})
            ON CONFLICT (instance_id, role, profile_id) DO UPDATE SET fingerprint = EXCLUDED.fingerprint,
                protocol_version = EXCLUDED.protocol_version, ready = EXCLUDED.ready,
                checked_at = EXCLUDED.checked_at, valid_until = EXCLUDED.valid_until
            """)
    int save(EmbeddingReadiness readiness);

    @Select("""
            SELECT instance_id, role, profile_id, fingerprint, protocol_version, ready, checked_at, valid_until
              FROM kb_embedding_readiness WHERE profile_id = #{profileId} AND valid_until > now()
            """)
    List<EmbeddingReadiness> findFresh(@Param("profileId") String profileId);

    @Delete("DELETE FROM kb_embedding_readiness WHERE valid_until < now() - interval '1 day'")
    int deleteExpired();
}
