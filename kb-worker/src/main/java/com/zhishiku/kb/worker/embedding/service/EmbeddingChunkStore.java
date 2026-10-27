package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingMath;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.model.RebuildChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmbeddingChunkStore {
    private final JdbcTemplate jdbc;

    public EmbeddingChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RebuildChunk read(long chunkId) {
        return read(chunkId, false);
    }

    public RebuildChunk readLocked(long chunkId) {
        return read(chunkId, true);
    }

    private RebuildChunk read(long chunkId, boolean locked) {
        var rows = jdbc.query("""
                SELECT c.id, c.doc_id, c.version_id, c.content, d.status, d.current_version_id
                  FROM kb_doc_chunk c JOIN kb_document d ON d.id = c.doc_id WHERE c.id = ?
                """ + (locked ? " FOR UPDATE OF c" : ""), (rs, row) -> new RebuildChunk(rs.getLong("id"), rs.getLong(
                        "doc_id"),
                rs.getLong("version_id"), rs.getString("content"), rs.getString("status"),
                rs.getObject("current_version_id", Long.class)), chunkId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void lockDocument(long docId) {
        jdbc.query("SELECT id FROM kb_document WHERE id = ? FOR UPDATE", (rs, row) -> rs.getLong(1), docId);
    }

    public int updateVector(RebuildBatch batch, RebuildBatch.Item item, float[] vector) {
        return jdbc.update("""
                        UPDATE kb_doc_chunk SET embedding = CAST(? AS vector), embedding_profile_id = ?, embed_model = ?
                         WHERE id = ? AND doc_id = ? AND version_id = ?
                        """, EmbeddingMath.toPgVector(vector), batch.profile().profileId(), batch.profile().model(),
                item.chunkId(), item.docId(), item.versionId());
    }

    public int finishItem(RebuildBatch batch, RebuildBatch.Item item, boolean skipped) {
        return jdbc.update("""
                        UPDATE kb_embedding_rebuild_item SET state = ?, run_claim_token = NULL,
                               lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL, updated_at = now()
                         WHERE run_id = ? AND chunk_id = ? AND doc_id = ? AND version_id = ? AND content_digest = ?
                           AND state = 'RUNNING' AND claim_token = ? AND run_claim_token = ? AND lease_owner = ?
                           AND lease_until > clock_timestamp()
                        """, skipped ? "SKIPPED" : "SUCCEEDED", batch.runId(), item.chunkId(), item.docId(),
                item.versionId(),
                item.contentDigest(), item.claimToken(), batch.runToken(), batch.owner());
    }

    public int completeBatch(RebuildBatch batch, int succeeded, int skipped) {
        return jdbc.update("""
                UPDATE kb_embedding_rebuild SET succeeded_count = succeeded_count + ?, skipped_count = skipped_count + ?,
                       lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL, updated_at = now()
                 WHERE run_id = ? AND state = 'REBUILDING' AND claim_token = ? AND lease_owner = ?
                   AND lease_until > clock_timestamp()
                """, succeeded, skipped, batch.runId(), batch.runToken(), batch.owner());
    }
}
