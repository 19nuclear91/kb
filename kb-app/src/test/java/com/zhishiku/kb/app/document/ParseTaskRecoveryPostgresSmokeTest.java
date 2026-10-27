package com.zhishiku.kb.app.document;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 可重复执行的可选 PostgreSQL 烟测。
 *
 * <p>需在 CI 上配置 KB_TEST_POSTGRES_URL、KB_TEST_POSTGRES_USER 与
 * KB_TEST_POSTGRES_PASSWORD，指向已执行迁移的数据库，用例才会真正运行。
 */
class ParseTaskRecoveryPostgresSmokeTest {

    @Test
    void migratedDatabaseExposesRecoveryTablesAndConstraints() throws Exception {
        String url = System.getenv("KB_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "KB_TEST_POSTGRES_URL 未配置，跳过 PostgreSQL 烟测");
        String user = System.getenv().getOrDefault("KB_TEST_POSTGRES_USER", "kb");
        String password = System.getenv().getOrDefault("KB_TEST_POSTGRES_PASSWORD", "kb_dev_pwd");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            assertThat(hasTable(statement, "kb_parse_task_dead_letter")).isTrue();
            assertThat(hasTable(statement, "kb_parse_task_event")).isTrue();
            assertThat(hasTable(statement, "kb_parse_alert_delivery")).isTrue();
            assertThat(hasColumn(statement, "kb_parse_task", "next_attempt_at")).isTrue();
            assertThat(hasColumn(statement, "kb_parse_task", "execution_generation")).isTrue();
        }
    }

    private static boolean hasTable(Statement statement, String table) throws Exception {
        try (ResultSet rs = statement.executeQuery("SELECT to_regclass('public." + table + "')")) {
            return rs.next() && rs.getString(1) != null;
        }
    }

    private static boolean hasColumn(Statement statement, String table, String column) throws Exception {
        try (ResultSet rs = statement.executeQuery("""
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = '""" + table + "' AND column_name = '" + column + "'")) {
            return rs.next();
        }
    }
}
