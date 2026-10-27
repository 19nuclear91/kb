package com.zhishiku.kb.app.embedding.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface EmbeddingCommandMapper {
    /**
     * 已持久化的重建命令幂等记录。
     *
     * @param actorUserId 执行操作的用户 ID
     * @param inputDigest 请求输入摘要，用于校验幂等重试
     * @param receipt     首次成功操作的序列化回执
     */
    record Operation(long actorUserId, String inputDigest, String receipt) {
    }

    @Select("""
            SELECT actor_user_id, input_digest, receipt::text AS receipt
              FROM kb_embedding_operation WHERE operation_domain = #{domain} AND request_id = #{requestId}
            """)
    Operation operation(@Param("domain") String domain, @Param("requestId") String requestId);

    @Insert("""
            INSERT INTO kb_embedding_rebuild (run_id, target_profile_id, created_by, state)
            VALUES (#{runId}, #{profileId}, #{actor}, 'QUIESCING')
            """)
    int createRun(@Param("runId") UUID runId, @Param("profileId") String profileId, @Param("actor") long actor);

    @Update("""
            UPDATE kb_embedding_control SET state = 'QUIESCING', target_profile_id = #{profileId},
                   run_id = #{runId}, epoch = epoch + 1
             WHERE id = 1 AND state IN ('ACTIVE', 'UNINITIALIZED') AND epoch = #{epoch} AND run_id IS NULL
            """)
    int start(@Param("runId") UUID runId, @Param("profileId") String profileId, @Param("epoch") long epoch);

    @Update("""
            UPDATE kb_embedding_rebuild SET state = resume_phase, resume_phase = NULL,
                   version = version + 1, claim_token = claim_token + 1, lease_owner = NULL, lease_until = NULL,
                   attempts = 0, next_attempt_at = NULL, error_code = NULL, updated_at = now()
             WHERE run_id = #{runId} AND state = 'BLOCKED' AND version = #{version}
               AND resume_phase IN ('QUIESCING', 'REBUILDING', 'VERIFYING')
            """)
    int resumeRun(@Param("runId") UUID runId, @Param("version") long version);

    @Update("""
            UPDATE kb_embedding_rebuild_item SET state = 'PENDING', attempts = 0, claim_token = claim_token + 1,
                   run_claim_token = NULL, lease_owner = NULL, lease_until = NULL,
                   next_attempt_at = NULL, updated_at = now()
             WHERE run_id = #{runId} AND state IN ('FAILED', 'RUNNING', 'PENDING')
            """)
    int resumeItems(@Param("runId") UUID runId);

    @Update("""
            UPDATE kb_embedding_control SET state = #{phase}, epoch = epoch + 1
             WHERE id = 1 AND state = 'BLOCKED' AND run_id = #{runId} AND epoch = #{epoch}
            """)
    int resumeControl(@Param("runId") UUID runId, @Param("epoch") long epoch, @Param("phase") String phase);

    @Insert("""
            INSERT INTO kb_embedding_operation
                (operation_id, operation_domain, request_id, actor_user_id, input_digest, run_id,
                 reason, receipt, before_state, after_state, epoch)
            VALUES (#{operationId}, #{domain}, #{requestId}, #{actor}, #{digest}, #{runId},
                    #{reason}, CAST(#{receipt} AS jsonb), #{before}, #{after}, #{epoch})
            """)
    int record(@Param("operationId") UUID operationId, @Param("domain") String domain,
               @Param("requestId") String requestId, @Param("actor") long actor, @Param("digest") String digest,
               @Param("runId") UUID runId, @Param("reason") String reason, @Param("receipt") String receipt,
               @Param("before") String before, @Param("after") String after, @Param("epoch") long epoch);
}
