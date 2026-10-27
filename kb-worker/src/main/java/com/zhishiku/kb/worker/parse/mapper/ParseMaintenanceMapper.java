package com.zhishiku.kb.worker.parse.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ParseMaintenanceMapper {
    /**
     * 维护期检查使用的解析任务领取快照。
     *
     * @param id         记录 ID
     * @param versionId  文档版本 ID
     * @param claimToken 领取时分配的栅栏令牌，用于拒绝迟到写入
     * @param epoch      嵌入控制代次，用于隔离旧任务与旧查询
     * @param attempts   已尝试执行次数
     * @param generation 任务执行代次，用于隔离重放前的旧任务
     */
    record Claim(long id, long versionId, long claimToken, Long epoch, int attempts, long generation) {
    }

    @Select("""
            SELECT id, version_id, claim_token, embedding_epoch AS epoch, attempts,
                   execution_generation AS generation
              FROM kb_parse_task WHERE status = 'running'
             ORDER BY id LIMIT 100 FOR UPDATE
            """)
    List<Claim> lockRunning();

    @Update("""
            UPDATE kb_parse_task
               SET status = 'pending', lease_owner = NULL, lease_until = NULL,
                   attempts = attempts - #{refund}, maintenance_deferred_token = claim_token,
                   claim_token = claim_token + 1, updated_at = now()
             WHERE id = #{id} AND status = 'running' AND claim_token = #{token}
               AND (embedding_epoch IS NULL OR embedding_epoch < #{epoch})
               AND (maintenance_deferred_token IS NULL OR maintenance_deferred_token <> claim_token)
            """)
    int defer(@Param("id") long id, @Param("token") long token, @Param("epoch") long epoch,
              @Param("refund") int refund);

    @Insert("""
            INSERT INTO kb_parse_task_event
                (task_id, version_id, execution_generation, attempt_no, event_type, dedupe_key)
            VALUES (#{claim.id}, #{claim.versionId}, #{claim.generation}, #{claim.attempts},
                    'MAINTENANCE_DEFERRED', #{dedupe})
            ON CONFLICT (dedupe_key) DO NOTHING
            """)
    int event(@Param("claim") Claim claim, @Param("dedupe") String dedupe);

    @Update("""
            UPDATE kb_document_version SET index_status = 'pending'
             WHERE id = #{id} AND index_status <> 'indexed'
            """)
    int pendingVersion(@Param("id") long id);

    @Select("SELECT count(*) FROM kb_parse_task WHERE status = 'running'")
    long runningCount();

    @Update("""
            UPDATE kb_embedding_rebuild SET state = 'REBUILDING', version = version + 1, updated_at = now()
             WHERE run_id = #{runId} AND state = 'QUIESCING' AND version = #{version}
            """)
    int advanceRun(@Param("runId") UUID runId, @Param("version") long version);

    @Update("""
            UPDATE kb_embedding_control SET state = 'REBUILDING'
             WHERE id = 1 AND state = 'QUIESCING' AND run_id = #{runId} AND epoch = #{epoch}
            """)
    int advanceControl(@Param("runId") UUID runId, @Param("epoch") long epoch);
}
