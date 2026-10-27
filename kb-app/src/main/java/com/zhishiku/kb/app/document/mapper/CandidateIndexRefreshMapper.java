package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.CandidateRefreshTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface CandidateIndexRefreshMapper {
    @Select("SELECT id, version_id, execution_generation, claim_token FROM kb_parse_task WHERE version_id = #{versionId} FOR UPDATE")
    CandidateRefreshTask lockTask(@Param("versionId") long versionId);

    @Select("SELECT id FROM kb_parse_task_dead_letter WHERE task_id = #{taskId} AND status = 'OPEN' ORDER BY id FOR UPDATE")
    List<Long> lockDeadLetters(@Param("taskId") long taskId);

    @Update("""
            UPDATE kb_parse_task_dead_letter SET status = 'REPLAYED', resolved_at = now(), resolved_by = #{actor},
                resolution_reason = '候选索引刷新'
            WHERE task_id = #{taskId} AND status = 'OPEN'
            """)
    int closeDeadLetters(@Param("taskId") long taskId, @Param("actor") long actor);

    @Update("""
            UPDATE kb_parse_task SET status = 'pending', attempts = 0, next_attempt_at = now(),
                lease_owner = NULL, lease_until = NULL, last_error = NULL, failure_class = NULL,
                failure_code = NULL, failure_summary = NULL, last_failure_at = NULL,
                execution_generation = #{generation}, claim_token = #{claimToken},
                embedding_profile_id = NULL, embedding_epoch = NULL, maintenance_deferred_token = NULL, updated_at = now()
            WHERE id = #{task.id} AND version_id = #{task.versionId}
                AND execution_generation = #{task.executionGeneration} AND claim_token = #{task.claimToken}
            """)
    int restart(@Param("task") CandidateRefreshTask task, @Param("generation") long generation, @Param("claimToken") long claimToken);

    @Update("""
            UPDATE kb_document_version SET index_status = 'pending', index_error = NULL, state_version = state_version + 1,
                indexed_profile_id = NULL, indexed_fingerprint = NULL, indexed_epoch = NULL, indexed_chunk_count = NULL
            WHERE id = #{versionId} AND state_version = #{expectedVersion} AND publication_evidence = 'NONE'
            """)
    int resetVersion(@Param("versionId") long versionId, @Param("expectedVersion") long expectedVersion);

    @Insert("""
            INSERT INTO kb_parse_task_event (task_id, version_id, execution_generation, event_type, actor_user_id, request_id, dedupe_key)
            SELECT id, version_id, execution_generation, 'REPLAYED', #{actor}, #{requestId},
                'candidate-refresh:' || id || ':' || execution_generation
            FROM kb_parse_task WHERE id = #{taskId} ON CONFLICT (dedupe_key) DO NOTHING
            """)
    int event(@Param("taskId") long taskId, @Param("actor") long actor, @Param("requestId") String requestId);
}
