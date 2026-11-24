package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchTransaction;
import com.zhishiku.kb.app.search.expansion.CandidateBatches;
import com.zhishiku.kb.app.search.expansion.NormalizedQuery;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** JDBC、事务及预算全部为替身，不连接服务，也不对 SQL 文本作断言。 */
class TermSnapshotReaderTest {
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-24T00:00:00Z");

    @Test
    void exactTextArrayBindingAndResultSubsetUseOneBudgetedConnectionAndFreeEveryResource() throws Exception {
        var f = new Fixture();
        var probe = f.probe(List.of("pm", "%_"));
        List<String> keys = List.of("pm", "%_", "a'b");
        var found = f.read(snapshot -> {
            assertThat(snapshot.state().revision()).isEqualTo(Long.MAX_VALUE);
            assertThat(snapshot.state().databaseTime()).isEqualTo(TIME);
            return snapshot.probe(keys);
        });
        assertThat(found).containsExactlyInAnyOrder("pm", "%_");
        assertThat(f.boundArrays).hasSize(1);
        assertThat(f.boundArrays.getFirst()).containsExactlyElementsOf(keys);
        verify(f.connection).createArrayOf(eq("text"), any(String[].class));
        verify(probe.statement()).setArray(1, f.arrays.getFirst());
        verify(probe.statement()).setQueryTimeout(1);
        verify(f.stateStatement).setQueryTimeout(1);
        verify(f.transactions).executeSnapshot(any());
        verify(f.transactions, never()).execute(any());
        verify(f.source).getConnection();
        verify(f.connection).close();
        verify(f.stateStatement).close();
        verify(f.stateRows).close();
        verify(probe.statement()).close();
        verify(probe.rows()).close();
        verify(f.arrays.getFirst()).free();
    }

    @Test
    void allBatchesReadOnlyOnceLoadedStateWithinSameSnapshotAndEmptyDictionaryIsNotFailure() throws Exception {
        var f = new Fixture();
        f.probe(List.of());
        f.probe(List.of());
        var query = NormalizedQuery.create("中".repeat(192), 8000, TermSnapshotReaderTest::checkpoint);
        var calls = new AtomicInteger();
        TermRows.State state = f.read(snapshot -> {
            TermRows.State initial = snapshot.state();
            for (var batch : new CandidateBatches(query, f.context::check)) {
                assertThat(snapshot.probe(batch.keys())).isEmpty();
                assertThat(snapshot.state()).isSameAs(initial);
                calls.incrementAndGet();
            }
            return initial;
        });
        assertThat(state.revision()).isEqualTo(Long.MAX_VALUE);
        assertThat(calls.get()).isEqualTo(2);
        verify(f.stateStatement).executeQuery();
        verify(f.transactions).executeSnapshot(any());
        verify(f.source).getConnection();
        assertThat(f.boundArrays).hasSize(2);
        for (Array array : f.arrays) {
            verify(array).free();
        }
    }

    @Test
    void zeroRevisionAndEmptyCandidateBatchRemainLegitimateWithoutAnArrayOrRelationRead() throws Exception {
        var f = new Fixture();
        when(f.stateRows.getObject("revision", Long.class)).thenReturn(0L);
        TermRows.State state = f.read(snapshot -> {
            assertThat(snapshot.probe(List.of())).isEmpty();
            return snapshot.state();
        });
        assertThat(state.revision()).isZero();
        verify(f.connection, never()).createArrayOf(anyString(), any());
        assertThat(f.boundArrays).isEmpty();
    }

    @Test
    void missingNullNegativeOrMultipleStateRowsNeverInvokeTheCallback() throws Exception {
        for (int condition = 0; condition < 4; condition++) {
            var f = new Fixture();
            if (condition == 0) {
                when(f.stateRows.next()).thenReturn(false);
            } else if (condition == 1) {
                when(f.stateRows.getObject("revision", Long.class)).thenReturn(null);
            } else if (condition == 2) {
                when(f.stateRows.getObject("revision", Long.class)).thenReturn(-1L);
            } else {
                when(f.stateRows.next()).thenReturn(true);
            }
            Function<TermSnapshot, Object> callback = mock(Function.class);
            unavailable(() -> f.read(callback));
            verifyNoInteractions(callback);
            verify(f.connection).close();
        }
        var time = new Fixture();
        when(time.stateRows.getObject("database_time", OffsetDateTime.class)).thenReturn(null);
        unavailable(() -> time.read(snapshot -> "unused"));
    }

    @Test
    void unexpectedNullDuplicateOrFailedRelationFactsAreUnavailableNotSuccessfulEmptyResults() throws Exception {
        for (List<String> rows : List.of(List.of("foreign"), Arrays.asList((String) null), List.of("pm", "pm"))) {
            var f = new Fixture();
            f.probe(rows);
            unavailable(() -> f.read(snapshot -> snapshot.probe(List.of("pm"))));
            verify(f.arrays.getFirst()).free();
        }
        var failed = new Fixture();
        var probe = failed.probe(List.of());
        when(probe.statement().executeQuery()).thenThrow(new SQLException("private-query-state"));
        unavailable(() -> failed.read(snapshot -> snapshot.probe(List.of("pm"))));
        verify(failed.arrays.getFirst()).free();
        var acquisition = new Fixture();
        when(acquisition.source.getConnection()).thenThrow(new SQLException("private-connection"));
        unavailable(() -> acquisition.read(snapshot -> snapshot.state()));
    }

    @Test
    void probeBoundsRejectBeforeBindingAndMaximumKeysUseOneArray() throws Exception {
        var f = new Fixture();
        f.probe(List.of());
        f.read(snapshot -> {
            for (List<String> invalid : List.of(List.of("a", "a"), List.of("a".repeat(65)), List.of(""),
                    IntStream.range(0, 8193).mapToObj(index -> "key" + index).toList())) {
                assertThatThrownBy(() -> snapshot.probe(invalid)).isInstanceOf(IllegalArgumentException.class);
            }
            List<String> maximum = IntStream.range(0, 8192).mapToObj(index -> "key" + index).toList();
            assertThat(snapshot.probe(maximum)).isEmpty();
            return snapshot.state();
        });
        assertThat(f.boundArrays).hasSize(1);
        assertThat(f.boundArrays.getFirst()).hasSize(8192);
    }

    @Test
    void cancellationAfterFirstQueryStopsFurtherBatchesAndClosesInsteadOfReturningPartialSuccess() throws Exception {
        var f = new Fixture();
        var first = f.probe(List.of());
        var second = f.probe(List.of());
        var cancelled = new AtomicBoolean();
        var failure = new SearchFailure(SearchFailure.Kind.CANCELLED);
        doAnswer(invocation -> {
            if (cancelled.get()) {
                throw failure;
            }
            return null;
        }).when(f.context).check();
        when(first.statement().executeQuery()).thenAnswer(invocation -> {
            cancelled.set(true);
            return first.rows();
        });
        var query = NormalizedQuery.create("中".repeat(192), 8000, TermSnapshotReaderTest::checkpoint);
        assertThatThrownBy(() -> f.read(snapshot -> {
            for (var batch : new CandidateBatches(query, f.context::check)) {
                snapshot.probe(batch.keys());
            }
            return snapshot.state();
        })).isSameAs(failure);
        verifyNoInteractions(second.statement(), second.rows());
        verify(f.arrays.getFirst()).free();
        verify(first.rows()).close();
        verify(f.connection).close();
        assertThat(f.boundArrays).hasSize(1);
    }

    @Test
    void snapshotEscapingCallbackIsDisabledAndMissingBudgetCannotOpenTheTransaction() throws Exception {
        var f = new Fixture();
        var escaped = new AtomicReference<TermSnapshot>();
        f.read(snapshot -> {
            escaped.set(snapshot);
            return snapshot.state();
        });
        assertThatThrownBy(() -> escaped.get().state()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> escaped.get().probe(List.of("pm"))).isInstanceOf(IllegalStateException.class);
        verify(f.source).getConnection();
        var missing = new Fixture();
        assertThatThrownBy(() -> missing.reader.read(snapshot -> snapshot.state())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(missing.transactions, missing.source);
    }

    @Test
    void recognizedSnapshotFailureMapsSafelyButCancellationAndProgrammingFailureAreNotReclassified() throws Exception {
        var f = new Fixture();
        doThrow(new DataAccessResourceFailureException("private-snapshot")).when(f.transactions).executeSnapshot(any());
        unavailable(() -> f.read(snapshot -> snapshot.state()));
        var timeout = new Fixture();
        var failure = new SearchFailure(SearchFailure.Kind.TIMEOUT);
        doThrow(failure).when(timeout.context).check();
        assertThatThrownBy(() -> timeout.read(snapshot -> snapshot.state())).isSameAs(failure);
        verifyNoInteractions(timeout.transactions, timeout.source);
        var programming = new Fixture();
        var unexpected = new IllegalStateException("private-program-error");
        assertThatThrownBy(() -> programming.read(snapshot -> {
            throw unexpected;
        })).isSameAs(unexpected);
        verify(programming.connection).close();
    }

    @Test
    void relationProjectionBindsExactKeyAndNinePlusTwoProofLimitsWithNoAliasesEmbedded() throws Exception {
        var f = new Fixture();
        var rows = f.facts(List.of(meaning(1, "PM", false), meaning(Long.MAX_VALUE, "产品经理", false)));
        TermSnapshot.Relations relations = f.read(snapshot -> snapshot.relations("pm"));
        assertThat(relations.terms()).extracting(TermSnapshot.Meaning::termId).containsExactly(1L, Long.MAX_VALUE);
        assertThat(relations.terms()).extracting(TermSnapshot.Meaning::version).containsExactly(Long.MAX_VALUE, Long.MAX_VALUE);
        assertThat(relations.correction()).isNull();
        verify(rows.statement()).setString(1, "pm");
        verify(rows.statement()).setString(2, "pm");
        verify(rows.statement()).setInt(3, 9);
        verify(rows.statement()).setString(4, "pm");
        verify(rows.statement()).setInt(5, 2);
        verify(f.connection, never()).createArrayOf(anyString(), any());
        var ninth = new Fixture();
        ninth.facts(LongStream.rangeClosed(1, 9).mapToObj(id -> meaning(id, "含义" + id, false)).toList());
        assertThat(ninth.read(snapshot -> snapshot.relations("pm")).terms()).hasSize(9);
    }

    @Test
    void singleCorrectionIsProjectedButInactiveNullMixedAndDuplicateFactsAreNeverTrusted() throws Exception {
        var f = new Fixture();
        f.facts(List.of(meaning(9, "报销", true)));
        var correction = f.read(snapshot -> snapshot.relations("报消"));
        assertThat(correction.terms()).isEmpty();
        assertThat(correction.correction().termId()).isEqualTo(9);
        for (String column : List.of("id", "version", "enabled", "correction", "canonical_name", "canonical_key", "description")) {
            var broken = new Fixture();
            var row = meaning(9, "报销", true);
            row.put(column, null);
            broken.facts(List.of(row));
            unavailable(() -> broken.read(snapshot -> snapshot.relations("报消")));
        }
        var inactive = new Fixture();
        var row = meaning(9, "报销", true);
        row.put("enabled", false);
        inactive.facts(List.of(row));
        unavailable(() -> inactive.read(snapshot -> snapshot.relations("报消")));
        for (List<Map<String, Object>> facts : List.of(List.of(meaning(1, "PM", false), meaning(9, "报销", true)),
                List.of(meaning(9, "报销", true), meaning(9, "报销", true)),
                List.of(meaning(1, "PM", false), meaning(1, "PM", false)))) {
            var invalid = new Fixture();
            invalid.facts(facts);
            unavailable(() -> invalid.read(snapshot -> snapshot.relations("pm")));
        }
    }

    @Test
    void aliasesAndOwnerProbesAreSeparatelyBoundedAndThe21stAliasOrThirdOwnerIsNotTruncated() throws Exception {
        var f = new Fixture();
        var aliases = f.facts(LongStream.rangeClosed(1, 20).mapToObj(TermSnapshotReaderTest::alias).toList());
        var owners = f.facts(List.of(Map.of("id", 1L), Map.of("id", Long.MAX_VALUE)));
        f.read(snapshot -> {
            assertThat(snapshot.aliases(Long.MAX_VALUE)).hasSize(20).allSatisfy(item -> assertThat(item.termId()).isEqualTo(Long.MAX_VALUE));
            assertThat(snapshot.owners("pm")).containsExactly(1L, Long.MAX_VALUE);
            return snapshot.state();
        });
        verify(aliases.statement()).setLong(1, Long.MAX_VALUE);
        verify(aliases.statement()).setInt(2, 21);
        verify(owners.statement()).setString(1, "pm");
        verify(owners.statement()).setString(2, "pm");
        verify(owners.statement()).setInt(3, 2);
        var tooMany = new Fixture();
        tooMany.facts(LongStream.rangeClosed(1, 21).mapToObj(TermSnapshotReaderTest::alias).toList());
        unavailable(() -> tooMany.read(snapshot -> snapshot.aliases(Long.MAX_VALUE)));
        var corruptOwners = new Fixture();
        corruptOwners.facts(List.of(Map.of("id", 1L), Map.of("id", 2L), Map.of("id", 3L)));
        unavailable(() -> corruptOwners.read(snapshot -> snapshot.owners("pm")));
        for (String field : List.of("term_id", "lookup_key", "kind")) {
            var broken = new Fixture();
            var fact = alias(1);
            fact.put(field, field.equals("term_id") ? 1L : "INVALID");
            broken.facts(List.of(fact));
            unavailable(() -> broken.read(snapshot -> snapshot.aliases(Long.MAX_VALUE)));
        }
    }

    private static Map<String, Object> meaning(long id, String name, boolean correction) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("version", Long.MAX_VALUE);
        row.put("canonical_name", name);
        row.put("canonical_key", TermInput.key(name));
        row.put("description", "释义");
        row.put("enabled", true);
        row.put("correction", correction);
        return row;
    }

    private static Map<String, Object> alias(long number) {
        var row = new LinkedHashMap<String, Object>();
        row.put("term_id", Long.MAX_VALUE);
        row.put("alias_text", "名称" + number);
        row.put("lookup_key", "名称" + number);
        row.put("kind", "ABBREVIATION");
        return row;
    }

    private static void unavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SearchFailure.class, error -> {
            assertThat(error.kind()).isEqualTo(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE);
            assertThat(error.status()).isEqualTo(503);
            assertThat(error.getCause()).isNull();
            assertThat(error.getMessage()).doesNotContain("private-");
        });
    }

    private static void checkpoint() {
    }

    private record Probe(PreparedStatement statement, ResultSet rows) {
    }

    private static final class Fixture {
        private final DataSource source = mock(DataSource.class);
        private final Connection connection = mock(Connection.class);
        private final SearchContext context = mock(SearchContext.class);
        private final SearchBudget.Window window = mock(SearchBudget.Window.class);
        private final SearchTransaction transactions = mock(SearchTransaction.class);
        private final PreparedStatement stateStatement = mock(PreparedStatement.class);
        private final ResultSet stateRows = mock(ResultSet.class);
        private final ArrayDeque<PreparedStatement> statements = new ArrayDeque<>();
        private final List<List<Object>> boundArrays = new ArrayList<>();
        private final List<Array> arrays = new ArrayList<>();
        private final TermSnapshotReader reader = new TermSnapshotReader(source, transactions);

        private Fixture() throws SQLException {
            when(source.getConnection()).thenReturn(connection);
            when(context.window()).thenReturn(window);
            when(window.queryTimeoutSeconds()).thenReturn(1);
            when(transactions.executeSnapshot(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
            statements.add(stateStatement);
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> statements.removeFirst());
            when(stateStatement.executeQuery()).thenReturn(stateRows);
            when(stateRows.next()).thenReturn(true, false);
            when(stateRows.getObject("revision", Long.class)).thenReturn(Long.MAX_VALUE);
            when(stateRows.getObject("database_time", OffsetDateTime.class)).thenReturn(TIME);
            when(connection.createArrayOf(anyString(), any())).thenAnswer(invocation -> {
                Object[] keys = invocation.getArgument(1);
                boundArrays.add(List.of(keys));
                Array array = mock(Array.class);
                arrays.add(array);
                return array;
            });
        }

        private Probe probe(List<String> keys) throws SQLException {
            var statement = mock(PreparedStatement.class);
            var rows = mock(ResultSet.class);
            var position = new AtomicInteger(-1);
            when(rows.next()).thenAnswer(invocation -> position.incrementAndGet() < keys.size());
            when(rows.getString("lookup_key")).thenAnswer(invocation -> keys.get(position.get()));
            when(statement.executeQuery()).thenReturn(rows);
            statements.add(statement);
            return new Probe(statement, rows);
        }

        private Probe facts(List<Map<String, Object>> facts) throws SQLException {
            var statement = mock(PreparedStatement.class);
            var rows = mock(ResultSet.class);
            var position = new AtomicInteger(-1);
            when(rows.next()).thenAnswer(invocation -> position.incrementAndGet() < facts.size());
            when(rows.getString(anyString())).thenAnswer(invocation -> {
                Object value = facts.get(position.get()).get(invocation.getArgument(0));
                return value == null ? null : value.toString();
            });
            when(rows.getObject(anyString(), any(Class.class))).thenAnswer(invocation -> facts.get(position.get()).get(invocation.getArgument(0)));
            when(statement.executeQuery()).thenReturn(rows);
            statements.add(statement);
            return new Probe(statement, rows);
        }

        private <T> T read(Function<TermSnapshot, T> action) {
            try (var current = mockStatic(SearchContext.class)) {
                current.when(SearchContext::current).thenReturn(context);
                return reader.read(action);
            }
        }
    }
}
