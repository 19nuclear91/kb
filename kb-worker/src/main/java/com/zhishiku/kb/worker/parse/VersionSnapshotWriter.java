package com.zhishiku.kb.worker.parse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.worker.parse.model.DocumentBlock;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.IntStream;

/** 只由发布事务调用，快照与检索分块共同提交。 */
@Component
public class VersionSnapshotWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public VersionSnapshotWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void replace(long documentId, ParseTask task, ParsePipeline.ParsedDocument parsed) {
        jdbc.update("DELETE FROM kb_document_version_snapshot WHERE version_id = ?", task.versionId());
        if (parsed.blocks().isEmpty()) {
            return;
        }
        long chars = parsed.blocks().stream().mapToLong(block ->
                block.text().codePointCount(0, block.text().length())).sum();
        int inserted = jdbc.update("""
                INSERT INTO kb_document_version_snapshot
                    (version_id, doc_id, task_id, execution_generation, parser_name, parser_version,
                     truncated, block_count, char_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.versionId(), documentId, task.id(), task.executionGeneration(),
                parsed.parserName(), parsed.parserVersion(), parsed.truncated(), parsed.blocks().size(), chars);
        if (inserted != 1) {
            throw new IllegalStateException("Snapshot header was not saved");
        }
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO kb_document_version_block
                    (version_id, block_idx, kind, text, heading_path, page_no, line_start, line_end,
                     char_start, char_end, locator, table_cells)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, IntStream.range(0, parsed.blocks().size()).boxed().toList(), parsed.blocks().size(),
                (ps, index) -> {
                    DocumentBlock block = parsed.blocks().get(index);
                    ps.setLong(1, task.versionId());
                    ps.setInt(2, index);
                    ps.setString(3, block.kind().name());
                    ps.setString(4, block.text());
                    ps.setString(5, block.headingPath());
                    ps.setObject(6, block.pageNo());
                    ps.setObject(7, block.source().lineStart());
                    ps.setObject(8, block.source().lineEnd());
                    ps.setObject(9, block.source().charStart());
                    ps.setObject(10, block.source().charEnd());
                    ps.setString(11, block.source().locator());
                    ps.setString(12, cells(block));
                });
        if (counts == null || Arrays.stream(counts).flatMapToInt(Arrays::stream).count() != parsed.blocks().size()
                || Arrays.stream(counts).flatMapToInt(Arrays::stream)
                .anyMatch(count -> count != 1 && count != Statement.SUCCESS_NO_INFO)) {
            throw new IllegalStateException("Incomplete snapshot block insertion");
        }
    }

    private String cells(DocumentBlock block) {
        if (block.kind() != DocumentBlock.Kind.TABLE) {
            return null;
        }
        try {
            return json.writeValueAsString(block.tableCells());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Snapshot cells could not be encoded");
        }
    }
}
