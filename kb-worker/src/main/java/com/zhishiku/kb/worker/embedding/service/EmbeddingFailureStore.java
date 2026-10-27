package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class EmbeddingFailureStore {
    private final JdbcTemplate jdbc;

    public EmbeddingFailureStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int failItems(RebuildBatch batch, String error) {
        return jdbc.update("""
                UPDATE kb_embedding_rebuild_item SET state = 'FAILED', claim_token = claim_token + 1,
                       lease_owner = NULL, lease_until = NULL, run_claim_token = NULL, error_code = ?, updated_at = now()
                 WHERE run_id = ? AND state = 'RUNNING' AND run_claim_token = ? AND lease_owner = ?
                """, error, batch.runId(), batch.runToken(), batch.owner());
    }

    public int retryItems(RebuildBatch batch, String error, int seconds) {
        return jdbc.update("""
                UPDATE kb_embedding_rebuild_item SET state = 'PENDING', claim_token = claim_token + 1,
                       lease_owner = NULL, lease_until = NULL, run_claim_token = NULL, error_code = ?,
                       next_attempt_at = clock_timestamp() + make_interval(secs => ?), updated_at = now()
                 WHERE run_id = ? AND state = 'RUNNING' AND run_claim_token = ? AND lease_owner = ?
                """, error, seconds, batch.runId(), batch.runToken(), batch.owner());
    }

    public int retryRun(RebuildBatch batch, String error, int seconds) {
        return jdbc.update("""
                UPDATE kb_embedding_rebuild SET lease_owner = NULL, lease_until = NULL, claim_token = claim_token + 1,
                       attempts = attempts + 1, error_code = ?,
                       next_attempt_at = clock_timestamp() + make_interval(secs => ?), updated_at = now()
                 WHERE run_id = ? AND state = 'REBUILDING' AND claim_token = ? AND lease_owner = ?
                   AND lease_until > clock_timestamp()
                """, error, seconds, batch.runId(), batch.runToken(), batch.owner());
    }

    public int blockRun(RebuildBatch batch, long version, String error) {
        return jdbc.update("""
                UPDATE kb_embedding_rebuild SET state = 'BLOCKED', resume_phase = 'REBUILDING', version = version + 1,
                       claim_token = claim_token + 1, lease_owner = NULL, lease_until = NULL,
                       next_attempt_at = NULL, error_code = ?, updated_at = now()
                 WHERE run_id = ? AND state = 'REBUILDING' AND version = ? AND claim_token = ? AND lease_owner = ?
                   AND lease_until > clock_timestamp()
                """, error, batch.runId(), version, batch.runToken(), batch.owner());
    }

    public int blockControl(RebuildBatch batch) {
        return jdbc.update("""
                UPDATE kb_embedding_control SET state = 'BLOCKED', epoch = epoch + 1
                 WHERE id = 1 AND state = 'REBUILDING' AND run_id = ? AND epoch = ?
                """, batch.runId(), batch.epoch());
    }

    public int blockEvent(RebuildBatch batch, long version, String error) {
        String requestId = batch.runId() + ":" + Math.addExact(version, 1);
        return jdbc.update("""
                        INSERT INTO kb_embedding_operation (operation_id, operation_domain, request_id, input_digest,
                               run_id, receipt, before_state, after_state, epoch)
                        VALUES (?, 'BLOCKED', ?, ?, ?, jsonb_build_object('errorCode', CAST(? AS text)),
                                'REBUILDING', 'BLOCKED', ?)
                        """, UUID.randomUUID(), requestId, EmbeddingDigest.text(requestId), batch.runId(), error,
                Math.addExact(batch.epoch(), 1));
    }
}
