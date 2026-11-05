package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.worker.telemetry.MonitoringJdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

/** 固定一行的状态／进度读取，不调用索引控制或持有业务行锁。 */
@Component
public class EmbeddingMetricsReader {
    static final String STATE_SQL = """
            SELECT c.state, c.run_id, r.run_id AS found_run_id, r.error_code,
                   r.total_count, r.succeeded_count, r.skipped_count
              FROM kb_embedding_control c
              LEFT JOIN kb_embedding_rebuild r ON r.run_id = c.run_id
             WHERE c.id = 1
            """;
    static final RowMapper<State> STATE_MAPPER = (rs, row) -> {
        boolean hasRun = rs.getObject("run_id") != null;
        if (hasRun && rs.getObject("found_run_id") == null) {
            throw new IllegalStateException("Embedding run unavailable");
        }
        return new State(rs.getString("state"), rs.getString("error_code"),
                hasRun ? count(rs, "total_count") : 0, hasRun ? count(rs, "succeeded_count") : 0,
                hasRun ? count(rs, "skipped_count") : 0);
    };

    public record State(String mode, String error, long total, long succeeded, long skipped) {
    }

    private static long count(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        if (rows.wasNull() || value < 0) {
            throw new IllegalStateException("Embedding progress unavailable");
        }
        return value;
    }

    private final JdbcTemplate jdbc;

    public EmbeddingMetricsReader(MonitoringJdbc jdbc) {
        this.jdbc = jdbc.template();
    }

    public State state() {
        return jdbc.queryForObject(STATE_SQL, STATE_MAPPER);
    }

    public long events(String operation) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM kb_embedding_operation WHERE operation_domain = ?", Long.class, operation);
        if (count == null) {
            throw new IllegalStateException("Embedding events unavailable");
        }
        return count;
    }
}
