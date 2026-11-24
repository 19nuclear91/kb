package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchTransaction;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** 所有批次使用同一有预算快照连接；只将回调的不可变结果带出事务。 */
@Service
public class TermSnapshotReader {
    private static final int KEY_LIMIT = 8192;
    private static final String STATE = "SELECT revision, clock_timestamp() AS database_time FROM kb_search_dictionary_state WHERE id = 1";
    private static final String PROBE = """
            SELECT candidate.lookup_key FROM unnest(CAST(? AS text[])) AS candidate(lookup_key)
            WHERE EXISTS (SELECT 1 FROM kb_search_term t
                WHERE t.enabled AND t.canonical_key = candidate.lookup_key COLLATE "C")
              OR EXISTS (SELECT 1 FROM kb_search_term_alias a JOIN kb_search_term t ON t.id = a.term_id
                WHERE t.enabled AND a.lookup_key = candidate.lookup_key COLLATE "C")
              OR EXISTS (SELECT 1 FROM kb_search_correction c JOIN kb_search_term t ON t.id = c.target_term_id
                WHERE c.enabled AND t.enabled AND c.wrong_key = candidate.lookup_key COLLATE "C")
            """;
    private static final String RELATIONS = """
            WITH terms AS (
              SELECT t.id, t.canonical_name, t.canonical_key, t.description, t.enabled, t.version
              FROM kb_search_term t WHERE t.enabled AND (t.canonical_key = ? OR EXISTS (
                SELECT 1 FROM kb_search_term_alias a WHERE a.term_id = t.id AND a.lookup_key = ?))
              ORDER BY t.id LIMIT ?
            ), correction AS (
              SELECT t.id, t.canonical_name, t.canonical_key, t.description, t.enabled, t.version
              FROM kb_search_correction c JOIN kb_search_term t ON t.id = c.target_term_id
              WHERE c.enabled AND t.enabled AND c.wrong_key = ? ORDER BY c.id LIMIT ?
            )
            SELECT terms.*, false AS correction FROM terms
            UNION ALL SELECT correction.*, true AS correction FROM correction
            ORDER BY correction, id
            """;
    private static final String ALIASES = """
            SELECT term_id, alias_text, lookup_key, kind FROM kb_search_term_alias
            WHERE term_id = ? ORDER BY lookup_key LIMIT ?
            """;
    private static final String OWNERS = """
            SELECT t.id FROM kb_search_term t WHERE t.enabled AND (t.canonical_key = ? OR EXISTS (
              SELECT 1 FROM kb_search_term_alias a WHERE a.term_id = t.id AND a.lookup_key = ?))
            ORDER BY t.id LIMIT ?
            """;
    private final DataSource source;
    private final SearchTransaction transactions;

    public TermSnapshotReader(DataSource source, SearchTransaction transactions) {
        this.source = source;
        this.transactions = transactions;
    }

    public <T> T read(Function<TermSnapshot, T> action) {
        Objects.requireNonNull(action);
        SearchContext context = SearchContext.current();
        if (context == null) {
            throw new IllegalStateException("词典读取缺少搜索预算");
        }
        context.check();
        try {
            return transactions.executeSnapshot(() -> {
                context.check();
                Connection connection = DataSourceUtils.getConnection(source);
                LocalSnapshot snapshot = null;
                try {
                    context.check();
                    snapshot = new LocalSnapshot(connection, context, readState(connection, context));
                    T result = action.apply(snapshot);
                    context.check();
                    return result;
                } finally {
                    if (snapshot != null) {
                        snapshot.close();
                    }
                    DataSourceUtils.releaseConnection(connection, source);
                }
            });
        } catch (DataAccessException | TransactionException unavailable) {
            throw unavailable();
        } catch (SearchFailure failure) {
            if (failure.kind() == SearchFailure.Kind.DEPENDENCY) {
                throw unavailable();
            }
            throw failure;
        }
    }

    private static TermRows.State readState(Connection connection, SearchContext context) {
        try (PreparedStatement statement = statement(connection, context, STATE); ResultSet rows = statement.executeQuery()) {
            context.check();
            if (!rows.next()) {
                throw unavailable();
            }
            Long revision = rows.getObject("revision", Long.class);
            OffsetDateTime time = rows.getObject("database_time", OffsetDateTime.class);
            context.check();
            if (revision == null || revision < 0 || time == null || rows.next()) {
                throw unavailable();
            }
            context.check();
            return new TermRows.State(revision, time);
        } catch (SQLException unavailable) {
            throw unavailable();
        }
    }

    private static PreparedStatement statement(Connection connection, SearchContext context, String sql) throws SQLException {
        context.check();
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            context.check();
            statement.setQueryTimeout(context.window().queryTimeoutSeconds());
            return statement;
        } catch (RuntimeException | SQLException failure) {
            try {
                statement.close();
            } catch (SQLException closeFailed) {
                failure.addSuppressed(closeFailed);
            }
            throw failure;
        }
    }

    private static final class LocalSnapshot implements TermSnapshot {
        private Connection connection;
        private SearchContext context;
        private final TermRows.State state;

        private LocalSnapshot(Connection connection, SearchContext context, TermRows.State state) {
            this.connection = connection;
            this.context = context;
            this.state = state;
        }

        @Override
        public TermRows.State state() {
            check();
            return state;
        }

        @Override
        public Set<String> probe(List<String> keys) {
            check();
            if (keys == null || keys.size() > KEY_LIMIT) {
                throw new IllegalArgumentException("词典候选键数量无效");
            }
            Set<String> expected = new LinkedHashSet<>();
            for (String key : keys) {
                check();
                if (key == null || key.isEmpty() || key.codePointCount(0, key.length()) > TermInput.EXPRESSION_LIMIT || !expected.add(key)) {
                    throw new IllegalArgumentException("词典候选键无效");
                }
            }
            if (keys.isEmpty()) {
                return Set.of();
            }
            try (var parameter = new ArrayParameter(connection.createArrayOf("text", keys.toArray(String[]::new)));
                 PreparedStatement statement = statement(connection, context, PROBE)) {
                statement.setArray(1, parameter.value);
                check();
                try (ResultSet rows = statement.executeQuery()) {
                    var found = new LinkedHashSet<String>();
                    check();
                    while (rows.next()) {
                        check();
                        String key = rows.getString("lookup_key");
                        if (key == null || !expected.contains(key) || !found.add(key)) {
                            throw unavailable();
                        }
                    }
                    check();
                    return Set.copyOf(found);
                }
            } catch (SQLException unavailable) {
                throw unavailable();
            }
        }

        @Override
        public Relations relations(String key) {
            checkKey(key);
            try (PreparedStatement statement = statement(connection, context, RELATIONS)) {
                statement.setString(1, key);
                statement.setString(2, key);
                statement.setInt(3, 9);
                statement.setString(4, key);
                statement.setInt(5, 2);
                check();
                try (ResultSet rows = statement.executeQuery()) {
                    var terms = new ArrayList<Meaning>();
                    Meaning correction = null;
                    long lastId = 0;
                    check();
                    while (rows.next()) {
                        check();
                        Meaning meaning = meaning(rows);
                        Boolean wrong = rows.getObject("correction", Boolean.class);
                        if (wrong == null) {
                            throw unavailable();
                        }
                        if (wrong) {
                            if (correction != null) {
                                throw unavailable();
                            }
                            correction = meaning;
                        } else {
                            if (terms.size() == 9 || meaning.termId() <= lastId || correction != null) {
                                throw unavailable();
                            }
                            terms.add(meaning);
                            lastId = meaning.termId();
                        }
                    }
                    check();
                    if (!terms.isEmpty() && correction != null) {
                        throw unavailable();
                    }
                    return new Relations(terms, correction);
                }
            } catch (SQLException unavailable) {
                throw unavailable();
            }
        }

        @Override
        public List<TermRows.Alias> aliases(long termId) {
            check();
            if (termId <= 0) {
                throw new IllegalArgumentException("词典组身份无效");
            }
            try (PreparedStatement statement = statement(connection, context, ALIASES)) {
                statement.setLong(1, termId);
                statement.setInt(2, TermInput.ALIAS_LIMIT + 1);
                check();
                try (ResultSet rows = statement.executeQuery()) {
                    var aliases = new ArrayList<TermRows.Alias>();
                    Set<String> keys = new LinkedHashSet<>();
                    check();
                    while (rows.next()) {
                        check();
                        if (aliases.size() == TermInput.ALIAS_LIMIT) {
                            throw unavailable();
                        }
                        Long id = rows.getObject("term_id", Long.class);
                        String text = rows.getString("alias_text");
                        String key = rows.getString("lookup_key");
                        String kind = rows.getString("kind");
                        try {
                            if (id == null || id != termId || !validText(text, TermInput.EXPRESSION_LIMIT)
                                    || !TermInput.key(text).equals(key) || !keys.add(key) || kind == null) {
                                throw unavailable();
                            }
                            aliases.add(new TermRows.Alias(id, text, key, TermRows.AliasKind.valueOf(kind)));
                        } catch (IllegalArgumentException invalid) {
                            throw unavailable();
                        }
                    }
                    check();
                    return List.copyOf(aliases);
                }
            } catch (SQLException unavailable) {
                throw unavailable();
            }
        }

        @Override
        public List<Long> owners(String key) {
            checkKey(key);
            try (PreparedStatement statement = statement(connection, context, OWNERS)) {
                statement.setString(1, key);
                statement.setString(2, key);
                statement.setInt(3, 2);
                check();
                try (ResultSet rows = statement.executeQuery()) {
                    var ids = new ArrayList<Long>();
                    long last = 0;
                    check();
                    while (rows.next()) {
                        check();
                        Long id = rows.getObject("id", Long.class);
                        if (id == null || id <= last || ids.size() == 2) {
                            throw unavailable();
                        }
                        ids.add(id);
                        last = id;
                    }
                    check();
                    return List.copyOf(ids);
                }
            } catch (SQLException unavailable) {
                throw unavailable();
            }
        }

        private void checkKey(String key) {
            check();
            if (key == null || key.isEmpty() || key.codePointCount(0, key.length()) > TermInput.EXPRESSION_LIMIT) {
                throw new IllegalArgumentException("词典比较键无效");
            }
        }

        private void check() {
            if (connection == null) {
                throw new IllegalStateException("词典快照已关闭");
            }
            context.check();
        }

        private void close() {
            connection = null;
            context = null;
        }

        @Override
        public String toString() {
            return "TermSnapshot[redacted]";
        }
    }

    private static TermSnapshot.Meaning meaning(ResultSet rows) throws SQLException {
        Long id = rows.getObject("id", Long.class);
        Long version = rows.getObject("version", Long.class);
        Boolean enabled = rows.getObject("enabled", Boolean.class);
        String name = rows.getString("canonical_name");
        String key = rows.getString("canonical_key");
        String description = rows.getString("description");
        if (id == null || id <= 0 || version == null || version < 0 || !Boolean.TRUE.equals(enabled)
                || !validText(name, TermInput.EXPRESSION_LIMIT) || !TermInput.key(name).equals(key)
                || !validText(description, TermInput.DESCRIPTION_LIMIT)) {
            throw unavailable();
        }
        return new TermSnapshot.Meaning(id, name, key, description, version);
    }

    private static boolean validText(String text, int limit) {
        try {
            return text != null && text.equals(TermInput.text(text, limit, false));
        } catch (TermException invalid) {
            return false;
        }
    }

    /** 以 try-with-resources 保证数组释放且不覆盖原取消／异常。 */
    private static final class ArrayParameter implements AutoCloseable {
        private final Array value;

        private ArrayParameter(Array value) {
            if (value == null) {
                throw unavailable();
            }
            this.value = value;
        }

        @Override
        public void close() throws SQLException {
            value.free();
        }
    }

    private static SearchFailure unavailable() {
        return new SearchFailure(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE);
    }
}
