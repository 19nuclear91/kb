package com.zhishiku.kb.worker.telemetry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** 独立模板不更改业务 JdbcTemplate 的超时或结果上限。 */
@Component
public class MonitoringJdbc {
    private final JdbcTemplate jdbc;

    public MonitoringJdbc(DataSource dataSource,
                          @Value("${kb.monitoring.query-timeout-seconds:3}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("Invalid monitoring query timeout");
        }
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(timeoutSeconds);
        jdbc.setMaxRows(1);
    }

    public JdbcTemplate template() {
        return jdbc;
    }
}
