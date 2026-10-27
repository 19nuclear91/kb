package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 负责把任务失败、退避和死信转移与文档状态放在同一事务内。
 */
@Service
public class ParseTaskRecoveryService {

    private final JdbcTemplate jdbc;
    private final TaskAlertOutbox alertOutbox;
    private final EmbeddingStateMapper state;

    public ParseTaskRecoveryService(JdbcTemplate jdbc, TaskAlertOutbox alertOutbox, EmbeddingStateMapper state) {
        this.jdbc = jdbc;
        this.alertOutbox = alertOutbox;
        this.state = state;
    }

    @Transactional
    public boolean scheduleRetry(ParseTask task, String owner,
                                 FailureClassifier.Classification classification,
                                 OffsetDateTime nextAttemptAt) {
        if (!lockActive(task)) return false;
        int updated = jdbc.update("""
                        UPDATE kb_parse_task
                           SET status = 'pending',
                               next_attempt_at = ?,
                               failure_class = ?,
                               failure_code = ?,
                               failure_summary = left(?, 2000),
                               last_failure_at = now(),
                               lease_until = NULL,
                               lease_owner = NULL,
                               last_error = left(?, 2000),
                               updated_at = now()
                         WHERE id = ? AND lease_owner = ? AND status = 'running'
                           AND lease_until > now()
                           AND claim_token = ? AND embedding_epoch = ? AND embedding_profile_id = ?
                        """, nextAttemptAt, classification.metricClass(), classification.code(),
                classification.summary(), classification.summary(), task.id(), owner,
                task.claimToken(), task.embeddingEpoch(), task.embeddingProfileId());
        if (updated != 1) return false;

        jdbc.update("""
                UPDATE kb_document_version
                   SET index_status = 'pending', index_error = left(?, 2000)
                 WHERE id = ?
                """, classification.summary(), task.versionId());
        insertEvent(task, "RETRY_SCHEDULED", classification, owner,
                "retry:" + task.id() + ":" + generation(task) + ":" + task.claimToken(), null);
        return true;
    }

    @Transactional
    public boolean deadLetter(ParseTask task, String owner,
                              FailureClassifier.Classification classification) {
        if (!lockActive(task)) return false;
        int updated = jdbc.update("""
                        UPDATE kb_parse_task
                           SET status = 'failed',
                               failure_class = ?,
                               failure_code = ?,
                               failure_summary = left(?, 2000),
                               last_failure_at = now(),
                               lease_until = NULL,
                               lease_owner = NULL,
                               last_error = left(?, 2000),
                               updated_at = now()
                         WHERE id = ? AND lease_owner = ? AND status = 'running'
                           AND lease_until > now()
                           AND claim_token = ? AND embedding_epoch = ? AND embedding_profile_id = ?
                        """, classification.metricClass(), classification.code(), classification.summary(),
                classification.summary(), task.id(), owner, task.claimToken(), task.embeddingEpoch(),
                task.embeddingProfileId());
        if (updated != 1) return false;

        jdbc.update("""
                UPDATE kb_document_version
                   SET index_status = 'failed', index_error = left(?, 2000)
                 WHERE id = ?
                """, classification.summary(), task.versionId());
        insertDeadLetter(task, classification);
        insertEvent(task, "DEAD_LETTERED", classification, owner,
                "dead-letter:" + task.id() + ":" + generation(task), null);
        alertOutbox.enqueue(task, "DEAD_LETTERED", classification,
                "dead-letter:" + task.id() + ":" + generation(task), true);
        return true;
    }

    /**
     * 租约失守只记录控制流事件，不改变任务业务状态。
     */
    @Transactional
    public void recordLeaseLost(ParseTask task, String owner) {
        if (!lockActive(task)) return;
        Integer held = jdbc.query("""
                        SELECT 1 FROM kb_parse_task WHERE id = ? AND lease_owner = ? AND status = 'running'
                           AND claim_token = ? AND embedding_epoch = ? AND embedding_profile_id = ?
                         FOR UPDATE
                        """, rs -> rs.next() ? 1 : null, task.id(), owner, task.claimToken(), task.embeddingEpoch(),
                task.embeddingProfileId());
        if (held == null) return;
        FailureClassifier.Classification classification = new FailureClassifier.Classification(
                FailureClassifier.FailureClass.UNKNOWN, "LEASE_LOST", "解析任务租约已失守", true);
        String dedupe = "lease-lost:" + task.id() + ":" + generation(task) + ":" + task.claimToken();
        insertEvent(task, "LEASE_LOST", classification, owner, dedupe, null);
        alertOutbox.enqueue(task, "LEASE_LOST", classification, dedupe, false);
    }

    /**
     * 把尝试已耗尽且没有有效租约的任务一次性落为失败并生成死信。
     * 事件和版本状态都在同一 SQL 事务内完成，重复巡检由唯一键吸收。
     */
    @Transactional
    public int sweepExhausted() {
        var control = state.lockShared();
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        if (!control.active()) return 0;
        Integer count = jdbc.queryForObject("""
                WITH exhausted AS (
                    UPDATE kb_parse_task
                       SET status = 'failed',
                           failure_class = COALESCE(failure_class, 'RETRYABLE'),
                           failure_code = COALESCE(failure_code, 'RETRY_EXHAUSTED'),
                           failure_summary = left(COALESCE(failure_summary, last_error,
                                                             '解析重试次数已耗尽'), 2000),
                           last_failure_at = COALESCE(last_failure_at, now()),
                           lease_until = NULL,
                           lease_owner = NULL,
                           updated_at = now()
                     WHERE attempts >= max_attempts
                       AND (status = 'pending'
                            OR (status = 'running' AND lease_until < now()))
                    RETURNING id, version_id, attempts, execution_generation,
                              failure_class, failure_code, failure_summary
                ), dead_letters AS (
                    INSERT INTO kb_parse_task_dead_letter
                        (task_id, version_id, execution_generation, attempts,
                         failure_class, failure_code, error_summary)
                    SELECT id, version_id, execution_generation, attempts,
                           COALESCE(failure_class, 'RETRYABLE'),
                           COALESCE(failure_code, 'RETRY_EXHAUSTED'),
                           left(COALESCE(failure_summary, '解析重试次数已耗尽'), 2000)
                      FROM exhausted
                    ON CONFLICT (task_id, execution_generation) DO NOTHING
                    RETURNING task_id, version_id, execution_generation,
                              attempts, failure_class, failure_code, error_summary
                ), events AS (
                    INSERT INTO kb_parse_task_event
                        (task_id, version_id, execution_generation, attempt_no,
                         event_type, failure_class, failure_code, error_summary, dedupe_key)
                    SELECT task_id, version_id, execution_generation, attempts,
                           'DEAD_LETTERED', failure_class, failure_code, error_summary,
                           'dead-letter:' || task_id || ':' || execution_generation
                      FROM dead_letters
                    ON CONFLICT (dedupe_key) DO NOTHING
                ), alerts AS (
                    INSERT INTO kb_parse_alert_delivery
                        (task_id, version_id, execution_generation, event_type,
                         failure_class, failure_code, error_summary, dedupe_key)
                    SELECT task_id, version_id, execution_generation, 'DEAD_LETTERED',
                           failure_class, failure_code, error_summary,
                           'dead-letter:' || task_id || ':' || execution_generation
                      FROM dead_letters
                    ON CONFLICT (dedupe_key) DO NOTHING
                ), versions AS (
                    UPDATE kb_document_version v
                       SET index_status = 'failed',
                           index_error = COALESCE(v.index_error, '解析重试次数已耗尽')
                     WHERE v.id IN (SELECT version_id FROM exhausted)
                )
                SELECT count(*) FROM dead_letters
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private void insertDeadLetter(ParseTask task, FailureClassifier.Classification classification) {
        jdbc.update("""
                        INSERT INTO kb_parse_task_dead_letter
                            (task_id, version_id, execution_generation, attempts,
                             failure_class, failure_code, error_summary)
                        VALUES (?, ?, ?, ?, ?, ?, left(?, 2000))
                        ON CONFLICT (task_id, execution_generation) DO NOTHING
                        """, task.id(), task.versionId(), generation(task), task.attempts(),
                classification.metricClass(), classification.code(), classification.summary());
    }

    private void insertEvent(ParseTask task, String eventType,
                             FailureClassifier.Classification classification,
                             String owner, String dedupeKey, String requestId) {
        jdbc.update("""
                        INSERT INTO kb_parse_task_event
                            (task_id, version_id, execution_generation, attempt_no, event_type,
                             failure_class, failure_code, error_summary, request_id, worker_owner, dedupe_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, left(?, 2000), ?, ?, ?)
                        ON CONFLICT (dedupe_key) DO NOTHING
                        """, task.id(), task.versionId(), generation(task), task.attempts(), eventType,
                classification.metricClass(), classification.code(), classification.summary(),
                requestId, owner, dedupeKey);
    }

    private static long generation(ParseTask task) {
        return task.executionGeneration() == null ? 0L : task.executionGeneration();
    }

    @Transactional
    public boolean renewLease(ParseTask task, String owner, int leaseSeconds) {
        if (!lockActive(task)) return false;
        return jdbc.update("""
                        UPDATE kb_parse_task SET lease_until = now() + make_interval(secs => ?), updated_at = now()
                         WHERE id = ? AND lease_owner = ? AND status = 'running' AND lease_until > now()
                           AND claim_token = ? AND embedding_epoch = ? AND embedding_profile_id = ?
                        """, leaseSeconds, task.id(), owner, task.claimToken(), task.embeddingEpoch(),
                task.embeddingProfileId()) == 1;
    }

    private boolean lockActive(ParseTask task) {
        if (!task.hasEmbeddingIdentity()) return false;
        var control = state.lockShared();
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        return control.active() && control.epoch() == task.embeddingEpoch()
                && control.activeProfileId().equals(task.embeddingProfileId())
                && control.activeFingerprint().equals(task.embeddingFingerprint());
    }
}
