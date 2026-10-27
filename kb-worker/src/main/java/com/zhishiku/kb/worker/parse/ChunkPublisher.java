package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.model.ParseIssue;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import com.zhishiku.kb.worker.parse.model.SourceLocation;
import com.zhishiku.kb.worker.parse.model.IndexPublicationCommit;
import com.zhishiku.kb.worker.parse.mapper.VersionPublicationMapper;
import com.zhishiku.kb.worker.parse.mapper.ReviewFactMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Arrays;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 同事务保存合法分块与索引身份、按固定策略发布并完成任务。indexed 不代表业务发布。
 */
@Service
public class ChunkPublisher {

    private final JdbcTemplate jdbc;
    private final EmbeddingStateMapper state;
    private final VersionPublicationMapper publication;
    private final ReviewFactMapper facts;

    public ChunkPublisher(JdbcTemplate jdbc, EmbeddingStateMapper state,
                           VersionPublicationMapper publication, ReviewFactMapper facts) {
        this.jdbc = jdbc;
        this.state = state;
        this.publication = publication;
        this.facts = facts;
    }

    @Transactional
    public boolean publish(ParseTask task, String owner, List<ParsePipeline.IndexedChunk> chunks) {
        return publish(task, owner, new ParsePipeline.ParsedDocument(chunks));
    }

    @Transactional
    public boolean publish(ParseTask task, String owner, ParsePipeline.ParsedDocument parsed) {
        if (parsed == null) throw new IllegalArgumentException("解析结果不能为空");
        if (parsed.hasErrors()) throw new IllegalArgumentException("解析结果包含错误，禁止发布");
        if (parsed.execution() == null || !parsed.execution().matches(task, owner)) return false;
        List<ParsePipeline.IndexedChunk> chunks = parsed.chunks();
        if (chunks.isEmpty()) throw new IllegalArgumentException("解析结果没有分块，禁止发布");
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).chunkIndex() != i) throw new IllegalArgumentException("分块序号必须完整且唯一");
        }
        new EmbeddingResult(parsed.execution().profile(), EmbeddingRole.DOCUMENT,
                chunks.stream().map(ParsePipeline.IndexedChunk::embedding).toList());
        if (!lockActive(task)) return false;
        Integer leased = jdbc.query(
                """
                        SELECT 1
                          FROM kb_parse_task
                         WHERE id = ? AND lease_owner = ? AND status = 'running'
                           AND lease_until > now()
                           AND version_id = ? AND claim_token = ? AND embedding_epoch = ?
                           AND embedding_profile_id = ?
                         FOR UPDATE
                        """,
                rs -> rs.next() ? rs.getInt(1) : null,
                task.id(), owner, task.versionId(), task.claimToken(), task.embeddingEpoch(),
                task.embeddingProfileId());
        if (leased == null) return false;

        // 独立语句明确 document -> version；不能依赖联合 FOR UPDATE 的行标记顺序。
        if (publication.lockDocumentForVersion(task.versionId()) == null) return false;

        VersionInfo version = jdbc.queryForObject(
                """
                        SELECT v.doc_id, v.version_no, d.status
                          FROM kb_document_version v
                          JOIN kb_document d ON d.id = v.doc_id
                         WHERE v.id = ?
                         FOR UPDATE OF v, d
                        """,
                (rs, rowNum) -> new VersionInfo(rs.getLong("doc_id"), rs.getInt("version_no"),
                        rs.getString("status")),
                task.versionId());
        if (version == null) throw new IllegalStateException("解析版本不存在：" + task.versionId());
        if ("deleted".equals(version.status())) {
            // 删除和解析完成存在竞态：删除事务提交后，迟到的 Worker 不能重新写入分块或 current_version。
            jdbc.update("""
                    UPDATE kb_parse_task
                       SET status = 'failed', lease_until = NULL, lease_owner = NULL,
                           last_error = '文档已删除', updated_at = now()
                     WHERE id = ? AND lease_owner = ?
                    """, task.id(), owner);
            return false;
        }

        var target = publication.lockState(task.versionId());
        if (target == null || target.getDocumentId() != version.docId() || target.getVersionId() != task.versionId())
            throw new IllegalStateException("Publication/version ownership mismatch");
        if (target.publication().evidence().isPublished()) return false;

        // 先清理该版本上一次可能留下的部分结果，再一次性写入新结果。
        lockDocumentRow(version.docId());
        jdbc.update("DELETE FROM kb_doc_chunk WHERE version_id = ?", task.versionId());
        jdbc.update("DELETE FROM kb_parse_issue WHERE version_id = ?", task.versionId());
        if (!parsed.issues().isEmpty()) {
            jdbc.batchUpdate(
                    """
                            INSERT INTO kb_parse_issue
                                (version_id, severity, code, message, page_no,
                                 line_start, line_end, char_start, char_end, locator)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    parsed.issues(), parsed.issues().size(),
                    (PreparedStatement ps, ParseIssue issue) -> {
                        SourceLocation location = issue.location();
                        ps.setLong(1, task.versionId());
                        ps.setString(2, issue.severity().name());
                        ps.setString(3, bounded(issue.code(), 128));
                        ps.setString(4, bounded(issue.message(), 4096));
                        setNullableInt(ps, 5, location.pageNo());
                        setNullableInt(ps, 6, location.lineStart());
                        setNullableInt(ps, 7, location.lineEnd());
                        setNullableInt(ps, 8, location.charStart());
                        setNullableInt(ps, 9, location.charEnd());
                        ps.setString(10, bounded(location.locator(), 1024));
                    });
        }
        int[][] insertedChunks = jdbc.batchUpdate(
                """
                        INSERT INTO kb_doc_chunk
                            (doc_id, version_id, space_id, security_level, chunk_idx,
                             content, page_no, heading_path, structure_type,
                             source_line_start, source_line_end, source_char_start, source_char_end,
                             source_locator, structure_metadata, embedding, embed_model, embedding_profile_id)
                        SELECT ?, ?, d.space_id, d.security_level, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector, ?, ?
                          FROM kb_document d
                         WHERE d.id = ?
                        """,
                chunks,
                chunks.size(),
                (PreparedStatement ps, ParsePipeline.IndexedChunk chunk) -> {
                    ps.setLong(1, version.docId());
                    ps.setLong(2, task.versionId());
                    ps.setInt(3, chunk.chunkIndex());
                    ps.setString(4, chunk.content());
                    if (chunk.pageNo() == null) ps.setObject(5, null);
                    else ps.setInt(5, chunk.pageNo());
                    ps.setString(6, chunk.headingPath());
                    ps.setString(7, chunk.kind().name());
                    setNullableInt(ps, 8, chunk.source().lineStart());
                    setNullableInt(ps, 9, chunk.source().lineEnd());
                    setNullableInt(ps, 10, chunk.source().charStart());
                    setNullableInt(ps, 11, chunk.source().charEnd());
                    ps.setString(12, bounded(chunk.source().locator(), 1024));
                    ps.setString(13, structureMetadata(chunk));
                    ps.setString(14, vectorLiteral(chunk.embedding()));
                    ps.setString(15, parsed.execution().profile().model());
                    ps.setString(16, parsed.execution().profile().profileId());
                    ps.setLong(17, version.docId());
                });
        if (insertedChunks == null || Arrays.stream(insertedChunks).flatMapToInt(Arrays::stream).count() != chunks.size()
                || Arrays.stream(insertedChunks).flatMapToInt(Arrays::stream)
                .anyMatch(rows -> rows != 1 && rows != Statement.SUCCESS_NO_INFO))
            throw new IllegalStateException("Incomplete chunk insertion");

        var commit = new IndexPublicationCommit(task.versionId(), bounded(parsed.parserName(), 128),
                bounded(parsed.parserVersion(), 128), parsed.truncated(), parsed.issues().size(),
                parsed.execution().profile().profileId(), parsed.execution().profile().fingerprint(),
                task.embeddingEpoch(), chunks.size());
        if (publication.indexed(commit) != 1) throw new LeaseLostException();
        if (target.canAutoPublish()) {
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            int published = publication.publishAuto(task.versionId(), commit.profileId(), commit.fingerprint(), now);
            if (published < 0 || published > 1) throw new IllegalStateException("Invalid publication result");
            if (published == 1) {
                facts.startCoverage(now);
                if (facts.append(CollaborationEvent.create(CollaborationKind.VERSION_PUBLISHED, version.docId(),
                        task.versionId(), null, null, now, null)) != 1)
                    throw new IllegalStateException("Publication fact was not saved");
            }
        }

        int completed = jdbc.update("""
                UPDATE kb_parse_task
                   SET status = 'succeeded', lease_until = NULL,
                       lease_owner = NULL, last_error = NULL, updated_at = now()
                 WHERE id = ? AND lease_owner = ? AND status = 'running'
                   AND claim_token = ? AND embedding_epoch = ? AND embedding_profile_id = ?
                """, task.id(), owner, task.claimToken(), task.embeddingEpoch(), task.embeddingProfileId());
        if (completed != 1) {
            throw new LeaseLostException();
        }
        return true;
    }

    /**
     * 取文档行锁，把发布与文档搬迁串行化。
     *
     * <p>搬迁事务在写之前取同一把锁（{@code SELECT space_id FROM kb_document WHERE id = ?
     * FOR UPDATE}），所以两者只能一前一后：发布在后 → 下面的
     * {@code INSERT ... SELECT d.space_id} 读到新归属；搬迁在后 → 它按 {@code doc_id}
     * 更新分块，覆盖发布刚提交的那批行。两条次序都收敛到"分块归属等于文档归属"。
     * 锁只取一侧不够：发布侧不取锁时，它的 {@code INSERT} 可以在搬迁的 {@code UPDATE}
     * 之后提交，那批新行就带着旧归属落地。
     *
     * <p>这和已经处理过的「删除 vs 迟到 Worker」是同一形状的竞态，解法也同形。
     *
     * <p><b>为什么显式再取一次</b>：上面那条版本查询的 {@code FOR UPDATE OF v, d} 其实
     * 已经锁住了同一行，同一事务内重复加锁没有代价。但那个子句读起来是为
     * {@code current_version_id} 的更新服务的——有人把它收窄成 {@code FOR UPDATE OF v}
     * 不会有任何报错，只会让搬迁重新变成偶发错。这条语句连同
     * {@code ChunkPublisherTest} 里的 {@code InOrder} 断言就是那个报错。
     *
     * <p><b>代价</b>：锁的持有时间覆盖分块批量写入，长文档会拉长持有时长，这是搬迁
     * 延迟与锁等待的主要来源（记在 {@code docs/parse-task-recovery-operations.md}）。
     */
    private void lockDocumentRow(long docId) {
        Integer locked = jdbc.query(
                "SELECT 1 FROM kb_document WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                docId);
        if (locked == null) throw new IllegalStateException("文档不存在：" + docId);
    }

    /**
     * 在任务失败前保存解析器已经产出的结构化问题，供前台诊断。
     */
    @Transactional
    public void recordFailure(ParseTask task, String owner, ParsePipeline.ParsedDocument parsed) {
        if (parsed == null || parsed.issues().isEmpty()) return;
        if (parsed.execution() == null || !parsed.execution().matches(task, owner) || !lockActive(task)) return;
        Integer leased = jdbc.query(
                """
                        SELECT 1 FROM kb_parse_task
                         WHERE id = ? AND lease_owner = ? AND status = 'running'
                           AND lease_until > now()
                           AND version_id = ? AND claim_token = ? AND embedding_epoch = ? AND embedding_profile_id = ?
                         FOR UPDATE
                        """,
                rs -> rs.next() ? rs.getInt(1) : null, task.id(), owner, task.versionId(), task.claimToken(),
                task.embeddingEpoch(), task.embeddingProfileId());
        if (leased == null) return;
        if (publication.lockDocumentForVersion(task.versionId()) == null) return;
        var target = publication.lockState(task.versionId());
        if (target == null || target.publication().evidence().isPublished() || "deleted".equals(target.getDocumentStatus())) return;
        jdbc.update("DELETE FROM kb_parse_issue WHERE version_id = ?", task.versionId());
        jdbc.batchUpdate(
                """
                        INSERT INTO kb_parse_issue
                            (version_id, severity, code, message, page_no,
                             line_start, line_end, char_start, char_end, locator)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                parsed.issues(), parsed.issues().size(),
                (PreparedStatement ps, ParseIssue issue) -> {
                    SourceLocation location = issue.location();
                    ps.setLong(1, task.versionId());
                    ps.setString(2, issue.severity().name());
                    ps.setString(3, bounded(issue.code(), 128));
                    ps.setString(4, bounded(issue.message(), 4096));
                    setNullableInt(ps, 5, location.pageNo());
                    setNullableInt(ps, 6, location.lineStart());
                    setNullableInt(ps, 7, location.lineEnd());
                    setNullableInt(ps, 8, location.charStart());
                    setNullableInt(ps, 9, location.charEnd());
                    ps.setString(10, bounded(location.locator(), 1024));
                });
        jdbc.update("""
                        UPDATE kb_document_version
                           SET parser_name = ?, parser_version = ?,
                               parse_truncated = ?, parse_issue_count = ?
                         WHERE id = ?
                        """, bounded(parsed.parserName(), 128), bounded(parsed.parserVersion(), 128),
                parsed.truncated(), parsed.issues().size(), task.versionId());
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) ps.setObject(index, null);
        else ps.setInt(index, value);
    }

    private static String bounded(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String structureMetadata(ParsePipeline.IndexedChunk chunk) {
        if (chunk.tableCells().isEmpty()) return null;
        StringBuilder json = new StringBuilder("{\"tableCells\":[");
        int rows = 0;
        for (List<String> row : chunk.tableCells()) {
            if (rows++ > 0) json.append(',');
            json.append('[');
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) json.append(',');
                json.append('\"').append(jsonEscape(bounded(row.get(i), 2048))).append('\"');
            }
            json.append(']');
            if (json.length() > 64000) {
                return "{\"truncated\":true,\"kind\":\"" + chunk.kind().name() + "\"}";
            }
        }
        return json.append("],\"kind\":\"").append(chunk.kind().name()).append("\"}").toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            float value = vector[i];
            if (!Float.isFinite(value)) throw new IllegalArgumentException("embedding 含 NaN 或 Infinity");
            if (i > 0) result.append(',');
            result.append(Float.toString(value));
        }
        return result.append(']').toString();
    }

    /**
     * 发布分块前读取的版本定位信息。
     *
     * @param docId     所属文档 ID
     * @param versionNo 版本序号
     * @param status    文档当前状态，用于识别已删除文档
     */
    private record VersionInfo(long docId, int versionNo, String status) {
    }

    private boolean lockActive(ParseTask task) {
        var control = state.lockShared();
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        return control.active() && control.epoch() == task.embeddingEpoch()
                && control.activeProfileId().equals(task.embeddingProfileId())
                && control.activeFingerprint().equals(task.embeddingFingerprint());
    }
}
