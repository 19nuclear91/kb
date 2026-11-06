package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 无标识聚合；订阅关闭后仍采样历史处置和清理积压。 */
@Component
public class LifecycleMetricsReader {
    static final String STOP_SQL = """
            SELECT event_id IS NOT NULL AS linked, initial_reception, commit_before, commit_after,
                   barrier_before, barrier_after, checked_at, after_check_min_nanos, after_check_max_nanos, reported_at
              FROM sys_identity_output_stop_evidence
             WHERE reported_at >= statement_timestamp() - interval '15 minutes'
             ORDER BY reported_at DESC LIMIT 1001
            """;
    static final RowMapper<OutputStopWindow> STOP_MAPPER = (rs, row) -> OutputStopWindow.calculate(rs.getBoolean("linked"),
            rs.getBoolean("initial_reception"), instant(rs, "commit_before"), instant(rs, "commit_after"),
            instant(rs, "barrier_before"), instant(rs, "barrier_after"), instant(rs, "checked_at"),
            rs.getLong("after_check_min_nanos"), rs.getLong("after_check_max_nanos"), instant(rs, "reported_at"));
    static final String SQL = """
            SELECT count(*) FILTER (WHERE status = 'UNMAPPED') AS unmapped,
                   count(*) FILTER (WHERE status = 'REVIEW') AS review,
                   count(*) FILTER (WHERE status = 'FAILED') AS failed,
                   min(created_at) AS oldest_item,
                   (SELECT count(*) FROM sys_identity_session_cleanup WHERE status = 'PENDING') AS cleanup,
                   (SELECT min(created_at) FROM sys_identity_session_cleanup WHERE status = 'PENDING') AS oldest_cleanup,
                   EXISTS (SELECT 1 FROM sys_identity_lifecycle
                     WHERE kind IN ('MANAGED', 'EMERGENCY') OR restriction != 'CLEAR') AS protection_required
              FROM sys_identity_lifecycle_item
             WHERE status IN ('UNMAPPED', 'REVIEW', 'FAILED') AND resolved_at IS NULL
            """;
    static final RowMapper<Summary> MAPPER = (rs, row) -> {
        Timestamp item = rs.getTimestamp("oldest_item"), cleanup = rs.getTimestamp("oldest_cleanup");
        return new Summary(rs.getLong("unmapped"), rs.getLong("review"), rs.getLong("failed"), rs.getLong("cleanup"),
                item == null ? null : item.toInstant(), cleanup == null ? null : cleanup.toInstant(),
                rs.getBoolean("protection_required"), false);
    };

    public record Summary(long unmapped, long review, long failed, long cleanup, Instant oldestItem,
                          Instant oldestCleanup, boolean protectionRequired, boolean emergencyReady,
                          long confirmedStops, long uncertainStops, double stopLower, double stopUpper) {
        public Summary(long unmapped, long review, long failed, long cleanup, Instant oldestItem,
                       Instant oldestCleanup, boolean protectionRequired, boolean emergencyReady) {
            this(unmapped, review, failed, cleanup, oldestItem, oldestCleanup, protectionRequired, emergencyReady, 0, 0, 0, 0);
        }

        public Summary {
            if (unmapped < 0 || review < 0 || failed < 0 || cleanup < 0
                    || confirmedStops < 0 || uncertainStops < 0 || !Double.isFinite(stopLower) || !Double.isFinite(stopUpper)
                    || stopLower < 0 || stopUpper < stopLower || (confirmedStops == 0 && (stopLower != 0 || stopUpper != 0))
                    || ((unmapped > 0 || review > 0 || failed > 0) != (oldestItem != null))
                    || ((cleanup > 0) != (oldestCleanup != null))) {
                throw new IllegalArgumentException("Invalid lifecycle metrics summary");
            }
        }
    }

    private final JdbcTemplate jdbc;
    private final ManagementInvariant management;

    public LifecycleMetricsReader(DataSource dataSource, ManagementInvariant management,
                                  @Value("${kb.monitoring.query-timeout-seconds:3}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("Invalid monitoring query timeout");
        }
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(timeoutSeconds);
        jdbc.setMaxRows(1001);
        this.management = management;
    }

    @Transactional(readOnly = true, timeout = 5, isolation = Isolation.REPEATABLE_READ)
    public Summary read() {
        Summary summary = jdbc.queryForObject(SQL, MAPPER);
        if (summary == null) {
            throw LifecycleFailures.unavailable();
        }
        boolean ready = management.emergencyRemainsAfter(ManagementChange.unchanged());
        var stops = jdbc.query(STOP_SQL, STOP_MAPPER);
        if (stops.size() > 1000) {
            throw LifecycleFailures.unavailable();
        }
        long confirmed = 0, uncertain = 0;
        double lower = 0, upper = 0;
        for (var stop : stops) {
            if (stop.quality() == OutputStopWindow.Quality.CONFIRMED) {
                confirmed++;
                lower = Math.max(lower, stop.lowerSeconds());
                upper = Math.max(upper, stop.upperSeconds());
            } else if (stop.quality() == OutputStopWindow.Quality.UNCERTAIN) {
                uncertain++;
            }
        }
        return new Summary(summary.unmapped(), summary.review(), summary.failed(), summary.cleanup(),
                summary.oldestItem(), summary.oldestCleanup(), summary.protectionRequired(), ready, confirmed, uncertain, lower, upper);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        Timestamp value = row.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
