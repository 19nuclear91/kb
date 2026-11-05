package com.zhishiku.kb.app.conversation.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

/** 只读账本聚合；不以领取时间、租约或 AI 观测覆盖过滤历史终结记录。 */
@Component
public class SettlementMetricsReader {
    static final String SQL = """
            SELECT count(*) FILTER (WHERE state = 'PENDING') AS pending,
                   count(*) FILTER (WHERE state = 'REVIEW') AS review,
                   min(finalized_at) FILTER (WHERE state = 'PENDING') AS oldest_finalized_at
              FROM kb_conversation_settlement
             WHERE state IN ('PENDING', 'REVIEW')
            """;
    static final RowMapper<Summary> MAPPER = (rs, row) -> {
        Timestamp oldest = rs.getTimestamp("oldest_finalized_at");
        return new Summary(rs.getLong("pending"), rs.getLong("review"), oldest == null ? null : oldest.toInstant());
    };

    public record Summary(long pending, long review, Instant oldestFinalizedAt) {
        public Summary {
            if (pending < 0 || review < 0 || (pending > 0 && oldestFinalizedAt == null)) {
                throw new IllegalArgumentException("Invalid settlement summary");
            }
        }
    }

    private final JdbcTemplate jdbc;

    public SettlementMetricsReader(DataSource dataSource,
                                   @Value("${kb.monitoring.query-timeout-seconds:3}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("Invalid monitoring query timeout");
        }
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(timeoutSeconds);
        jdbc.setMaxRows(1);
    }

    public Summary read() {
        return jdbc.queryForObject(SQL, MAPPER);
    }
}
