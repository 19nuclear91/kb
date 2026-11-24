package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.assistance.SearchContextFailure;
import com.zhishiku.kb.app.search.assistance.SignedSearchContext;
import com.zhishiku.kb.app.search.assistance.TermCursor;
import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TermReadServiceTest {
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-24T00:00:00.123456Z");

    @Test
    void limits20And100FetchOnlyOneLookaheadAndNeverLoadItsAliases() {
        for (int limit : new int[]{20, 100}) {
            var f = new Fixture();
            var input = new TermInputs.Query(null, null, limit, null);
            when(f.mapper.terms(any(), any(), any(), anyInt())).thenReturn(LongStream.rangeClosed(1, limit + 1).mapToObj(TermReadServiceTest::term).toList());
            when(f.cursor.issue(any(), eq(7L), eq((long) limit))).thenReturn("next");
            var page = f.service.terms(input);
            assertThat(page.items()).hasSize(limit);
            assertThat(page.items().getLast().termId()).isEqualTo(Integer.toString(limit));
            assertThat(page.hasMore()).isTrue();
            assertThat(page.nextCursor()).isEqualTo("next");
            assertThat(page.dictionaryRevision()).isEqualTo("7");
            verify(f.mapper).terms(null, null, null, limit + 1);
            verify(f.mapper, never()).aliases(limit + 1);
            verify(f.mapper, times(limit)).aliases(anyLong());
            verify(f.database).read(any());
        }
    }

    @Test
    void literalPatternAndSubmittedEnabledConditionsAreBoundWithExactCursorPosition() {
        var f = new Fixture();
        var query = new TermInputs.Query(" %_\\ ", false, 20, "cursor");
        var conditions = new TermCursor.Conditions(9, SignedSearchContext.Purpose.TERM_DIRECTORY, null, "%_\\", false, 20);
        when(f.cursor.read("cursor", conditions, 7)).thenReturn(new TermCursor.Position(Long.MAX_VALUE, 7, "current"));
        var page = f.service.terms(query);
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        verify(f.mapper).terms("%\\%\\_\\\\%", false, Long.MAX_VALUE, 21);
        var order = inOrder(f.access, f.mapper, f.cursor);
        order.verify(f.access).requireRead();
        order.verify(f.mapper).state();
        order.verify(f.cursor).read("cursor", conditions, 7);
        order.verify(f.mapper).terms("%\\%\\_\\\\%", false, Long.MAX_VALUE, 21);
    }

    @Test
    void emptyDictionaryIsRealRevisionZeroWhileStateOrPageFailureNeverBecomesEmptySuccess() {
        var f = new Fixture();
        when(f.mapper.state()).thenReturn(new TermRows.State(0, TIME));
        var page = f.service.terms(new TermInputs.Query(null, null, 20, null));
        assertThat(page.items()).isEmpty();
        assertThat(page.dictionaryRevision()).isEqualTo("0");
        var state = new Fixture();
        when(state.mapper.state()).thenReturn(null);
        failure(() -> state.service.terms(new TermInputs.Query(null, null, 20, null)), TermException.Reason.UNAVAILABLE);
        var rows = new Fixture();
        when(rows.mapper.terms(any(), any(), any(), anyInt())).thenReturn(null);
        failure(() -> rows.service.terms(new TermInputs.Query(null, null, 20, null)), TermException.Reason.UNAVAILABLE);
    }

    @Test
    void staleOrUnavailableCursorPreventsReadingAnotherPageAndUsesFixedTermErrors() {
        for (SearchContextFailure.Kind kind : new SearchContextFailure.Kind[]{SearchContextFailure.Kind.DICTIONARY_CHANGED,
                SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE, SearchContextFailure.Kind.INVALID_INPUT}) {
            var f = new Fixture();
            when(f.cursor.read(any(), any(), anyLong())).thenThrow(new SearchContextFailure(kind));
            TermException.Reason expected = switch (kind) {
                case DICTIONARY_CHANGED -> TermException.Reason.DICTIONARY_CHANGED;
                case INVALID_INPUT -> TermException.Reason.INVALID_INPUT;
                default -> TermException.Reason.UNAVAILABLE;
            };
            failure(() -> f.service.terms(new TermInputs.Query(null, null, 20, "cursor")), expected);
            verify(f.mapper, never()).terms(any(), any(), any(), anyInt());
            verify(f.mapper, never()).aliases(anyLong());
        }
    }

    @Test
    void unauthorizedDirectoriesAndDetailsNeverReadRevisionTargetOrCursor() {
        var f = new Fixture();
        when(f.access.requireRead()).thenThrow(new TermException(TermException.Reason.FORBIDDEN));
        failure(() -> f.service.terms(new TermInputs.Query(null, null, 20, null)), TermException.Reason.FORBIDDEN);
        failure(() -> f.service.corrections(new TermInputs.Query(null, null, 20, null)), TermException.Reason.FORBIDDEN);
        failure(() -> f.service.term(1), TermException.Reason.FORBIDDEN);
        failure(() -> f.service.correction(1), TermException.Reason.FORBIDDEN);
        verifyNoInteractions(f.mapper, f.cursor);
    }

    @Test
    void detailAndCorrectionPagePreserveBigIdsAndBothEnabledFactsFromOneSnapshot() {
        var f = new Fixture();
        var target = new TermRows.Term(Long.MAX_VALUE, "当前名称", "当前名称", "释义", false, Long.MAX_VALUE, TIME, TIME);
        var mapping = new TermRows.Correction(9007199254740993L, "错词", "错词", Long.MAX_VALUE, true, 0, TIME, TIME);
        when(f.mapper.term(Long.MAX_VALUE)).thenReturn(target);
        when(f.mapper.correction(mapping.id())).thenReturn(mapping);
        var detail = f.service.correction(mapping.id());
        assertThat(detail.dictionaryRevision()).isEqualTo("7");
        assertThat(detail.correction().correctionId()).isEqualTo("9007199254740993");
        assertThat(detail.correction().enabled()).isTrue();
        assertThat(detail.correction().available()).isFalse();
        assertThat(detail.correction().target().enabled()).isFalse();
        assertThat(detail.correction().target().termId()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(detail.correction().target().version()).isEqualTo(Long.toString(Long.MAX_VALUE));
        when(f.mapper.corrections(any(), any(), any(), anyInt())).thenReturn(List.of(mapping));
        var page = f.service.corrections(new TermInputs.Query("错词", true, 100, null));
        assertThat(page.items()).containsExactly(detail.correction());
        verify(f.mapper).corrections("%错词%", true, null, 101);
        verify(f.database, times(2)).read(any());
    }

    @Test
    void termDetailContainsBoundedAliasesAndMissingOrCorruptRowsFailWithoutPartialView() {
        var f = new Fixture();
        when(f.mapper.term(1)).thenReturn(term(1));
        when(f.mapper.aliases(1)).thenReturn(List.of(new TermRows.Alias(1, "OA", "oa", TermRows.AliasKind.ABBREVIATION)));
        assertThat(f.service.term(1).term().aliases()).containsExactly(new TermViews.Alias("OA", TermRows.AliasKind.ABBREVIATION, false));
        failure(() -> f.service.term(2), TermException.Reason.NOT_FOUND);
        when(f.mapper.aliases(1)).thenReturn(LongStream.rangeClosed(1, 21).mapToObj(id -> new TermRows.Alias(1, "别名" + id, "别名" + id, TermRows.AliasKind.SYNONYM)).toList());
        failure(() -> f.service.term(1), TermException.Reason.UNAVAILABLE);
        var invalidTarget = new Fixture();
        when(invalidTarget.mapper.correction(1)).thenReturn(new TermRows.Correction(1, "错词", "错词", 2, true, 0, TIME, TIME));
        failure(() -> invalidTarget.service.correction(1), TermException.Reason.UNAVAILABLE);
    }

    private static TermRows.Term term(long id) {
        return new TermRows.Term(id, "名称" + id, "名称" + id, "释义", true, 0, TIME, TIME);
    }

    private static void failure(Runnable action, TermException.Reason expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(expected));
    }

    private static final class Fixture {
        private final TermDatabase database = mock(TermDatabase.class);
        private final TermAccess access = mock(TermAccess.class);
        private final TermMapper mapper = mock(TermMapper.class);
        private final TermCursor cursor = mock(TermCursor.class);
        private final TermReadService service = new TermReadService(database, access, mapper, cursor);

        private Fixture() {
            when(database.read(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
            when(access.requireRead()).thenReturn(9L);
            when(mapper.state()).thenReturn(new TermRows.State(7, TIME));
            when(mapper.ambiguities(any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0).stream()
                    .map(key -> new TermRows.Expression(key, false)).toList());
        }
    }
}
