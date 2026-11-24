package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.assistance.SearchContextFailure;
import com.zhishiku.kb.app.search.assistance.SignedSearchContext;
import com.zhishiku.kb.app.search.assistance.TermCursor;
import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TermMeaningQueryTest {
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-24T00:00:00Z");

    @Test
    void canonicalAndAliasAmbiguityComeFromAllActiveRelationsNotJustCurrentPage() {
        var f = new Fixture();
        when(f.mapper.terms(any(), any(), any(), anyInt())).thenReturn(List.of(term(1, "项目管理", true)));
        when(f.mapper.aliases(1)).thenReturn(List.of(alias(1, "PM")));
        var outsideGroupEnabled = new AtomicBoolean(true);
        when(f.mapper.ambiguities(any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0).stream()
                .map(key -> new TermRows.Expression(key, outsideGroupEnabled.get())).toList());
        var page = f.service.terms(new TermInputs.Query(null, null, 20, null));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().canonicalAmbiguous()).isTrue();
        assertThat(page.items().getFirst().aliases().getFirst().ambiguous()).isTrue();
        outsideGroupEnabled.set(false);
        when(f.mapper.state()).thenReturn(new TermRows.State(8, TIME));
        var current = f.service.terms(new TermInputs.Query(null, null, 20, null));
        assertThat(current.items().getFirst().canonicalAmbiguous()).isFalse();
        assertThat(current.items().getFirst().aliases().getFirst().ambiguous()).isFalse();
        assertThat(current.dictionaryRevision()).isEqualTo("8");
        verify(f.mapper, never()).meanings(any(), any(), anyInt());
        verify(f.mapper, times(2)).ambiguities(any());
    }

    @Test
    void pageProbesDeduplicatedExpressionKeysInOneAssociationQueryWithoutEmbeddingAllMeanings() {
        var f = new Fixture();
        when(f.mapper.terms(any(), any(), any(), anyInt())).thenReturn(List.of(term(1, "项目管理", true), term(2, "产品经理", true)));
        when(f.mapper.aliases(1)).thenReturn(List.of(alias(1, "PM")));
        when(f.mapper.aliases(2)).thenReturn(List.of(alias(2, "PM")));
        when(f.mapper.ambiguities(any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0).stream()
                .map(key -> new TermRows.Expression(key, key.equals("pm"))).toList());
        var page = f.service.terms(new TermInputs.Query(null, null, 20, null));
        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).allSatisfy(item -> assertThat(item.aliases().getFirst().ambiguous()).isTrue());
        var keys = ArgumentCaptor.forClass(List.class);
        verify(f.mapper).ambiguities(keys.capture());
        assertThat(keys.getValue()).containsExactly("项目管理", "pm", "产品经理");
        verify(f.mapper, never()).meanings(any(), any(), anyInt());
    }

    @Test
    void sharedMeaningsValidateOwnedExpressionThenPageOnlyActiveMinimalGroups() {
        var f = new Fixture();
        when(f.mapper.term(500)).thenReturn(term(500, "旧术语", false));
        when(f.mapper.aliases(500)).thenReturn(List.of(alias(500, "PM")));
        when(f.mapper.meanings("pm", null, 21)).thenReturn(LongStream.rangeClosed(1, 21).mapToObj(id -> term(id, "含义" + id, true)).toList());
        when(f.cursor.issue(any(), eq(7L), eq(20L))).thenReturn("next");
        var page = f.service.meanings(500, new TermInputs.MeaningQuery(" pm ", 20, null));
        assertThat(page.items()).hasSize(20);
        assertThat(page.items().getLast().termId()).isEqualTo("20");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("next");
        var conditions = new TermCursor.Conditions(9, SignedSearchContext.Purpose.TERM_MEANINGS, 500L, "pm", true, 20);
        verify(f.cursor).read(null, conditions, 7);
        verify(f.mapper).meanings("pm", null, 21);
        verify(f.mapper).aliases(500);
        verify(f.mapper, never()).aliases(1);
        verify(f.mapper, never()).ambiguities(any());
    }

    @Test
    void foreignExpressionAndCrossTargetCursorNeverEnumerateSharedGroups() {
        var f = new Fixture();
        when(f.mapper.term(1)).thenReturn(term(1, "办公自动化", true));
        when(f.mapper.aliases(1)).thenReturn(List.of(alias(1, "OA")));
        failure(() -> f.service.meanings(1, new TermInputs.MeaningQuery("PM", 20, null)), TermException.Reason.INVALID_INPUT);
        verify(f.cursor, never()).read(any(), any(), anyLong());
        verify(f.mapper, never()).meanings(any(), any(), anyInt());
        var other = new Fixture();
        when(other.mapper.term(1)).thenReturn(term(1, "OA", true));
        when(other.cursor.read(any(), any(), anyLong())).thenThrow(new SearchContextFailure(SearchContextFailure.Kind.INVALID_INPUT));
        failure(() -> other.service.meanings(1, new TermInputs.MeaningQuery("OA", 20, "foreign-cursor")), TermException.Reason.INVALID_INPUT);
        verify(other.mapper, never()).meanings(any(), any(), anyInt());
    }

    @Test
    void missingQualificationNeverReadsTargetDescriptionOrAssociationFacts() {
        var f = new Fixture();
        when(f.access.requireRead()).thenThrow(new TermException(TermException.Reason.FORBIDDEN));
        failure(() -> f.service.meanings(1, new TermInputs.MeaningQuery("PM", 20, null)), TermException.Reason.FORBIDDEN);
        verifyNoInteractions(f.mapper, f.cursor);
    }

    @Test
    void missingAmbiguityOrInactiveMeaningCannotBeSilentlyProjectedAsUnambiguousOrEmpty() {
        var flags = new Fixture();
        when(flags.mapper.term(1)).thenReturn(term(1, "OA", true));
        when(flags.mapper.ambiguities(any())).thenReturn(List.of());
        failure(() -> flags.service.term(1), TermException.Reason.UNAVAILABLE);
        var meanings = new Fixture();
        when(meanings.mapper.term(1)).thenReturn(term(1, "OA", true));
        when(meanings.mapper.meanings("oa", null, 21)).thenReturn(List.of(term(2, "含义", false)));
        failure(() -> meanings.service.meanings(1, new TermInputs.MeaningQuery("OA", 20, null)), TermException.Reason.UNAVAILABLE);
    }

    private static TermRows.Term term(long id, String name, boolean enabled) {
        return new TermRows.Term(id, name, TermInput.key(name), "释义", enabled, 0, TIME, TIME);
    }

    private static TermRows.Alias alias(long id, String name) {
        return new TermRows.Alias(id, name, TermInput.key(name), TermRows.AliasKind.ABBREVIATION);
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
        }
    }
}
