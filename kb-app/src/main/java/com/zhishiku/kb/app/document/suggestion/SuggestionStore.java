package com.zhishiku.kb.app.document.suggestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.conversation.UsageSettlement;
import com.zhishiku.kb.common.suggestion.SuggestionData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class SuggestionStore {
    private static final String JOB = """
            SELECT request_id, execution_id, actor_id, auth_epoch, department_id, doc_id, space_id,
                source_version_id, metadata_version, tree_version, deadline, generation_status, reason_code,
                dispatched_at, reserved_tokens, usage_status, prompt_tokens, completion_tokens,
                planned_charge, settled_charge, settlement_status
            FROM kb_document_metadata_suggestion WHERE request_id = ?
            """;
    private final JdbcClient sql;
    private final ObjectMapper json;

    public SuggestionStore(JdbcClient sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    public boolean insert(UUID id, UUID execution, SuggestionAccess.Context c, OffsetDateTime deadline, String sessionDigest) {
        return sql.sql("""
                INSERT INTO kb_document_metadata_suggestion (request_id, execution_id, actor_id, auth_epoch,
                    department_id, doc_id, space_id, source_version_id, metadata_version, tree_version, deadline, session_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING
                """).params(id, execution, c.actorId(), c.authEpoch(), c.departmentId(), c.docId(), c.spaceId(),
                c.versionId(), c.metadataVersion(), c.treeVersion(), deadline, sessionDigest).update() == 1;
    }

    public boolean activeSession(UUID id, String token) {
        return token != null && sql.sql("SELECT EXISTS (SELECT 1 FROM kb_document_metadata_suggestion WHERE request_id=? AND session_digest=?)")
                .params(id, RequestSignature.digest(token)).query(Boolean.class).single();
    }

    public boolean bindSnapshot(UUID id) {
        return sql.sql("""
                UPDATE kb_document_metadata_suggestion j SET snapshot_task_id=s.task_id, snapshot_generation=s.execution_generation
                FROM kb_document_version_snapshot s
                WHERE j.request_id=? AND j.generation_status='RUNNING' AND j.snapshot_task_id IS NULL
                    AND s.version_id=j.source_version_id AND s.doc_id=j.doc_id
                """).param(id).update() == 1;
    }

    public boolean snapshotMatches(UUID id) {
        return sql.sql("""
                SELECT EXISTS (SELECT 1 FROM kb_document_metadata_suggestion j
                    JOIN kb_document_version_snapshot s ON s.version_id=j.source_version_id AND s.doc_id=j.doc_id
                    JOIN kb_parse_task t ON t.version_id=s.version_id AND t.id=s.task_id AND t.execution_generation=s.execution_generation
                    WHERE j.request_id=? AND s.task_id=j.snapshot_task_id AND s.execution_generation=j.snapshot_generation)
                """).param(id).query(Boolean.class).single();
    }

    public SuggestionJob find(UUID id) {
        return sql.sql(JOB).param(id).query(SuggestionJob.class).optional().orElse(null);
    }

    public SuggestionJob lock(UUID id) {
        return sql.sql(JOB + " FOR UPDATE").param(id).query(SuggestionJob.class).optional().orElse(null);
    }

    public SuggestionJob lockOwned(UUID id, long actor, long doc) {
        return sql.sql(JOB + " AND actor_id=? AND doc_id=? FOR UPDATE")
                .params(id, actor, doc).query(SuggestionJob.class).optional().orElse(null);
    }

    public record StoredResult(OffsetDateTime expiresAt, String payload) {
        @Override
        public String toString() {
            return "StoredResult";
        }

    }

    public StoredResult result(UUID id, OffsetDateTime now) {
        return sql.sql("""
                SELECT expires_at, payload::text AS payload FROM kb_document_metadata_suggestion_result
                WHERE request_id = ? AND expires_at > ?
                """).params(id, now).query(StoredResult.class).optional().orElse(null);
    }

    public SuggestionData.Result decode(StoredResult stored) {
        try {
            return json.readValue(stored.payload(), SuggestionData.Result.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("整理结果不可用");
        }
    }

    public void saveResult(UUID id, OffsetDateTime expiry, SuggestionData.Result result) {
        try {
            sql.sql("""
                    INSERT INTO kb_document_metadata_suggestion_result (request_id, expires_at, payload)
                    VALUES (?, ?, CAST(? AS jsonb))
                    """).params(id, expiry, json.writeValueAsString(result)).update();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("整理结果不可用");
        }
    }

    public int clean(OffsetDateTime now) {
        return sql.sql("""
                DELETE FROM kb_document_metadata_suggestion_result WHERE request_id IN (
                    SELECT request_id FROM kb_document_metadata_suggestion_result WHERE expires_at <= ?
                    ORDER BY expires_at, request_id LIMIT 256 FOR UPDATE SKIP LOCKED)
                """).param(now).update();
    }

    public boolean terminate(UUID id, String status, String reason) {
        return sql.sql("""
                UPDATE kb_document_metadata_suggestion SET generation_status=?, reason_code=?, finished_at=clock_timestamp(), session_digest=NULL
                WHERE request_id=? AND generation_status='RUNNING'
                """).param(1, status).param(2, reason).param(3, id).update() == 1;
    }

    public boolean reserve(UUID id, long amount) {
        return sql.sql("""
                UPDATE kb_document_metadata_suggestion SET reserved_tokens=?, settlement_status='RESERVING'
                WHERE request_id=? AND generation_status='RUNNING' AND settlement_status='NOT_REQUIRED'
                    AND deadline > clock_timestamp()
                """).params(amount, id).update() == 1;
    }

    public boolean confirmReserve(UUID id, long amount) {
        return sql.sql("""
                UPDATE kb_document_metadata_suggestion SET settlement_status='RESERVED'
                WHERE request_id=? AND reserved_tokens=? AND generation_status='RUNNING'
                    AND settlement_status='RESERVING' AND deadline > clock_timestamp()
                """).params(id, amount).update() == 1;
    }

    public boolean dispatch(UUID id) {
        return sql.sql("""
                UPDATE kb_document_metadata_suggestion SET dispatched_at=clock_timestamp()
                WHERE request_id=? AND generation_status='RUNNING' AND settlement_status='RESERVED'
                    AND dispatched_at IS NULL AND deadline > clock_timestamp()
                """).param(id).update() == 1;
    }

    public void freeze(UUID id, UsageSettlement.Value usage) {
        sql.sql("""
                UPDATE kb_document_metadata_suggestion SET usage_status=?, prompt_tokens=?, completion_tokens=?,
                    planned_charge=?, settlement_status=CASE WHEN reserved_tokens IS NULL THEN 'NOT_REQUIRED' ELSE 'PENDING' END
                WHERE request_id=? AND usage_status IS NULL AND generation_status <> 'RUNNING'
                """).param(1, usage.status()).param(2, usage.promptTokens()).param(3, usage.completionTokens())
                .param(4, usage.chargedTokens()).param(5, id).update();
    }

    public void recordLateUsage(UUID id, long prompt, long completion) {
        sql.sql("""
                INSERT INTO kb_document_metadata_suggestion_event (request_id, fact)
                SELECT ?, jsonb_build_object('reasonCode','LATE_USAGE_REVIEW','promptTokens',CAST(? AS bigint),
                    'completionTokens',CAST(? AS bigint))
                WHERE NOT EXISTS (SELECT 1 FROM kb_document_metadata_suggestion_event
                    WHERE request_id=? AND fact->>'reasonCode'='LATE_USAGE_REVIEW')
                """).params(id, prompt, completion, id).update();
    }

    public void settle(UUID id, boolean confirmed) {
        sql.sql("""
                UPDATE kb_document_metadata_suggestion SET settlement_status=?,
                    settled_charge=CASE WHEN ? THEN planned_charge ELSE NULL END
                WHERE request_id=? AND settlement_status='PENDING'
                """)
                .params(confirmed ? "SETTLED" : "REVIEW", confirmed, id).update();
    }

    public List<UUID> expiredExecutions() {
        return sql.sql("""
                SELECT request_id FROM kb_document_metadata_suggestion WHERE usage_status IS NULL
                    AND deadline <= clock_timestamp() ORDER BY deadline, request_id LIMIT 64
                """).query(UUID.class).list();
    }

    public List<UUID> pendingCharges() {
        return sql.sql("""
                UPDATE kb_document_metadata_suggestion j SET next_settlement_at=clock_timestamp() + interval '30 seconds'
                FROM (SELECT request_id FROM kb_document_metadata_suggestion WHERE settlement_status='PENDING'
                    AND next_settlement_at <= clock_timestamp() ORDER BY next_settlement_at, request_id
                    LIMIT 4 FOR UPDATE SKIP LOCKED) due
                WHERE j.request_id=due.request_id RETURNING j.request_id
                """).query(UUID.class).list();
    }
}
