package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingDigest;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class EmbeddingVerificationStore {
    /**
     * 覆盖扫描中的分块标识与向量有效性结果。
     *
     * @param id    记录 ID
     * @param valid 分块向量及其配置身份是否有效
     */
    public record Chunk(long id, boolean valid) {
    }

    private final JdbcTemplate jdbc;

    public EmbeddingVerificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String VALID_VECTOR = """
            c.embedding_profile_id = ? AND c.embedding IS NOT NULL AND vector_dims(c.embedding) = 1024
            AND NOT EXISTS (SELECT 1 FROM unnest(c.embedding::real[]) AS v(value)
                            WHERE value IN ('NaN'::real, 'Infinity'::real, '-Infinity'::real))
            AND EXISTS (SELECT 1 FROM unnest(c.embedding::real[]) AS v(value) WHERE value <> 0)
            AND i.state = 'SUCCEEDED' AND i.doc_id = c.doc_id AND i.version_id = c.version_id
            AND i.content_digest = encode(sha256(convert_to(c.content, 'UTF8')), 'hex')
            """;
    private static final String SCOPE = """
              FROM kb_doc_chunk c JOIN kb_document d ON d.id = c.doc_id
              LEFT JOIN kb_embedding_rebuild_item i ON i.run_id = ? AND i.chunk_id = c.id
             WHERE d.status <> 'deleted' AND c.version_id = d.current_version_id
            """;

    public List<Chunk> page(UUID runId, String profileId, long after) {
        return jdbc.query("SELECT c.id, COALESCE((" + VALID_VECTOR + "), FALSE) AS valid " + SCOPE
                        + " AND c.id > ? ORDER BY c.id LIMIT 100",
                (rs, row) -> new Chunk(rs.getLong("id"), rs.getBoolean("valid")), profileId, runId, after);
    }

    public boolean invalidCoverage(UUID runId, String profileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 " + SCOPE
                + " AND NOT COALESCE((" + VALID_VECTOR + "), FALSE))", Boolean.class, runId, profileId));
    }

    public boolean missingChunks() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM kb_document d
                 WHERE d.status <> 'deleted' AND d.current_version_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM kb_doc_chunk c WHERE c.doc_id = d.id AND c.version_id = d.current_version_id))
                """, Boolean.class));
    }

    public boolean unfinished(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM kb_embedding_rebuild_item WHERE run_id = ? AND state NOT IN ('SUCCEEDED', 'SKIPPED'))
                """, Boolean.class, runId));
    }

    public boolean executionOutstanding(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM kb_parse_task WHERE status = 'running')
                    OR EXISTS (SELECT 1 FROM kb_embedding_rebuild_item WHERE run_id = ?
                                AND (lease_owner IS NOT NULL OR run_claim_token IS NOT NULL))
                    OR EXISTS (SELECT 1 FROM kb_embedding_rebuild WHERE run_id = ? AND lease_until > clock_timestamp())
                """, Boolean.class, runId, runId));
    }

    public boolean targetsReady(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT targets_ready FROM kb_embedding_rebuild WHERE run_id =" +
                        " ?",
                Boolean.class, runId));
    }

    public void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '8s'");
    }

    public int beginRun(EmbeddingRun run) {
        return jdbc.update("""
                UPDATE kb_embedding_rebuild SET state = 'VERIFYING', version = version + 1, claim_token = claim_token + 1,
                       lease_owner = NULL, lease_until = NULL, updated_at = now()
                 WHERE run_id = ? AND state = 'REBUILDING' AND version = ? AND targets_ready
                """, run.runId(), run.version());
    }

    public int beginControl(EmbeddingControl control) {
        return jdbc.update("""
                UPDATE kb_embedding_control SET state = 'VERIFYING', epoch = epoch + 1
                 WHERE id = 1 AND run_id = ? AND epoch = ? AND state = 'REBUILDING'
                """, control.runId(), control.epoch());
    }

    public int blockRun(EmbeddingRun run, String error) {
        return jdbc.update("""
                UPDATE kb_embedding_rebuild SET state = 'BLOCKED', resume_phase = 'VERIFYING', version = version + 1,
                       claim_token = claim_token + 1, lease_owner = NULL, lease_until = NULL, error_code = ?, updated_at = now()
                 WHERE run_id = ? AND version = ? AND state = 'VERIFYING'
                """, error, run.runId(), run.version());
    }

    public int blockControl(EmbeddingControl control) {
        return jdbc.update("""
                UPDATE kb_embedding_control SET state = 'BLOCKED', epoch = epoch + 1
                 WHERE id = 1 AND run_id = ? AND epoch = ? AND state = 'VERIFYING'
                """, control.runId(), control.epoch());
    }

    public int activateRun(EmbeddingRun run) {
        return jdbc.update("""
                UPDATE kb_embedding_rebuild SET state = 'SUCCEEDED', version = version + 1, claim_token = claim_token + 1,
                       lease_owner = NULL, lease_until = NULL, completed_at = now(), updated_at = now(), error_code = NULL
                 WHERE run_id = ? AND state = 'VERIFYING' AND version = ?
                """, run.runId(), run.version());
    }

    public int activateControl(EmbeddingControl control) {
        return jdbc.update("""
                UPDATE kb_embedding_control SET state = 'ACTIVE', active_profile_id = target_profile_id,
                       target_profile_id = NULL, run_id = NULL, epoch = epoch + 1
                 WHERE id = 1 AND state = 'VERIFYING' AND run_id = ? AND epoch = ? AND target_profile_id = ?
                """, control.runId(), control.epoch(), control.targetProfileId());
    }

    public int event(EmbeddingControl control, EmbeddingRun run, String domain, String error) {
        String request = run.runId() + ":" + Math.addExact(run.version(), 1);
        return jdbc.update("""
                        INSERT INTO kb_embedding_operation (operation_id, operation_domain, request_id, input_digest, run_id,
                               receipt, before_state, after_state, epoch)
                        VALUES (?, ?, ?, ?, ?, jsonb_build_object('errorCode', CAST(? AS text)), 'VERIFYING', ?, ?)
                        """, UUID.randomUUID(), domain, request, EmbeddingDigest.text(request), run.runId(), error,
                "SUCCEEDED".equals(domain) ? "ACTIVE" : "BLOCKED", Math.addExact(control.epoch(), 1));
    }
}
