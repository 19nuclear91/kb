package com.zhishiku.kb.app.space;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 空间授权库表的可选 PostgreSQL 烟测，可重复执行。
 * 需在 CI 上配置 KB_TEST_POSTGRES_URL、KB_TEST_POSTGRES_USER 与
 * KB_TEST_POSTGRES_PASSWORD，指向已执行迁移的数据库，用例才会真正运行。
 *
 * <p>每个用例都在事务内改数据并回滚，不会污染目标库。
 */
class SpaceAuthorizationPostgresSmokeTest {

    /**
     * 与 {@code PermissionMapper.findVisibleSpaceIds} 等价的纯 SQL。
     *
     * <p>手写副本的漂移风险由 {@code PermissionMapperArchiveFilterTest} 看住——
     * 它直接断言注解里的 SQL 形状。这与 {@code db/verify_permission.sql}
     * 手写 ACL 谓词的做法一致。
     */
    private static final String VISIBLE_SPACES = """
            SELECT DISTINCT a.space_id
              FROM kb_space_acl a
              JOIN kb_space s ON s.id = a.space_id
             WHERE a.permission = ?
               AND (s.archived = false OR a.permission = 'manage')
               AND a.principal_key = ANY (?)
             ORDER BY 1
            """;

    @Test
    void migratedDatabaseExposesSpaceVersionColumnDefaultingToZero() throws Exception {
        String url = requireUrl();
        try (Connection connection = connect(url);
             Statement statement = connection.createStatement()) {
            assertThat(hasColumn(statement, "kb_space", "version")).isTrue();
            try (ResultSet rs = statement.executeQuery("""
                    SELECT column_default, is_nullable
                      FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'kb_space'
                       AND column_name = 'version'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("column_default")).contains("0");
                assertThat(rs.getString("is_nullable")).isEqualTo("NO");
            }
        }
    }

    @Test
    @DisplayName("空间归档后退出读权限位可见范围，但仍留在 manage 可见范围内")
    void archivingRemovesSpaceFromReadScopeButKeepsItManageable() throws Exception {
        String url = requireUrl();
        try (Connection connection = connect(url)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // 自建主体与空间，不依赖开发种子数据是否存在。
                statement.execute("""
                        INSERT INTO kb_space (code, name) VALUES
                            ('SMOKE-ARCHIVE', '归档烟测空间')
                        """);
                long spaceId = scalar(connection, "SELECT id FROM kb_space WHERE code = 'SMOKE-ARCHIVE'");
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO kb_space_acl (space_id, principal_type, principal_id, permission)
                        VALUES (?, 1, 999999, 'view'), (?, 1, 999999, 'manage')
                        """)) {
                    insert.setLong(1, spaceId);
                    insert.setLong(2, spaceId);
                    insert.executeUpdate();
                }
                String[] principals = {"1:999999"};

                assertThat(visibleSpaces(connection, "view", principals))
                        .as("未归档时 view 可见")
                        .contains(spaceId);
                assertThat(visibleSpaces(connection, "manage", principals))
                        .as("未归档时 manage 可见")
                        .contains(spaceId);

                try (PreparedStatement archive = connection.prepareStatement(
                        "UPDATE kb_space SET archived = true WHERE id = ?")) {
                    archive.setLong(1, spaceId);
                    assertThat(archive.executeUpdate()).isEqualTo(1);
                }

                assertThat(visibleSpaces(connection, "view", principals))
                        .as("归档后 view 必须看不到该空间")
                        .doesNotContain(spaceId);
                assertThat(visibleSpaces(connection, "manage", principals))
                        .as("归档后 manage 必须仍能看到，否则无人可取消归档")
                        .contains(spaceId);
            } finally {
                connection.rollback();
            }
        }
    }

    private static List<Long> visibleSpaces(Connection connection, String permission,
                                            String[] principalKeys) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(VISIBLE_SPACES)) {
            query.setString(1, permission);
            query.setArray(2, connection.createArrayOf("text", principalKeys));
            try (ResultSet rs = query.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static String requireUrl() {
        String url = System.getenv("KB_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "KB_TEST_POSTGRES_URL 未配置，跳过 PostgreSQL 烟测");
        return url;
    }

    private static Connection connect(String url) throws Exception {
        String user = System.getenv().getOrDefault("KB_TEST_POSTGRES_USER", "kb");
        String password = System.getenv().getOrDefault("KB_TEST_POSTGRES_PASSWORD", "kb_dev_pwd");
        return DriverManager.getConnection(url, user, password);
    }

    private static boolean hasColumn(Statement statement, String table, String column) throws Exception {
        try (ResultSet rs = statement.executeQuery("""
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = '""" + table
                + "' AND column_name = '" + column + "'")) {
            return rs.next();
        }
    }
}
